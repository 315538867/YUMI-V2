package com.yumi.catalog.product;

import com.yumi.calculation.DecimalPolicy;
import com.yumi.calculation.product.ProductPricing;
import com.yumi.catalog.changelog.MasterDataChangeLogService;
import com.yumi.catalog.product.internal.CatalogReference;
import com.yumi.catalog.product.internal.ProductInputResolver;
import com.yumi.catalog.product.internal.ProductRepository;
import com.yumi.catalog.product.internal.ProductRow;
import com.yumi.identity.AuditContext;
import com.yumi.shared.error.ApiException;
import com.yumi.shared.error.ApiFieldError;
import com.yumi.shared.error.ErrorCode;
import com.yumi.shared.numbering.SequenceAllocator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 商品应用服务（任务 2.2/2.3/2.15/2.24）：
 * 创建分配 P 编号并快照全局单价；编辑/启停写追加式变更日志；停用商品仍可编辑。
 * 新建与编辑共用 {@link ProductInputResolver} 解析计算输入，再调用集中计算模块整体重算，
 * 服务端不采信客户端派生金额。缝边只保存默认值（默认缝边剪袋类型 + 缝边价格），
 * 商品自身总成本按不缝边剪袋口径，缝边剪袋变体以 {@link SeamBudgetView} 单列返回。
 */
@Service
public class ProductService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";

    private final ProductRepository repository;
    private final CatalogReference reference;
    private final ProductInputResolver resolver;
    private final SequenceAllocator sequenceAllocator;
    private final MasterDataChangeLogService changeLog;
    private final AuditContext auditContext;

    public ProductService(ProductRepository repository, CatalogReference reference,
                          ProductInputResolver resolver, SequenceAllocator sequenceAllocator,
                          MasterDataChangeLogService changeLog, AuditContext auditContext) {
        this.repository = repository;
        this.reference = reference;
        this.resolver = resolver;
        this.sequenceAllocator = sequenceAllocator;
        this.changeLog = changeLog;
        this.auditContext = auditContext;
    }

    // ---------- 查询 ----------

    public List<ProductSummary> list(String status, String name) {
        return repository.find(status, name).stream().map(this::toSummary).toList();
    }

    public ProductDetail get(long id) {
        return toDetail(requireRow(id));
    }

    // ---------- 只读试算 ----------

    /** 新建试算（任务 2.16）：与保存共用输入解析与集中公式，不分配编号、不写任何业务事实。 */
    @Transactional(readOnly = true)
    public ProductPreview previewCreate(CreateProductRequest request) {
        return toPreview(resolver.forCreate(request, new ArrayList<>()));
    }

    /** 编辑试算（任务 2.16）：沿用 PATCH 缺省合并与引用快照语义，需携带商品版本号。 */
    @Transactional(readOnly = true)
    public ProductPreview previewUpdate(long id, UpdateProductRequest request) {
        var existing = requireRow(id);
        var errors = new ArrayList<ApiFieldError>();
        if (request.version() == null) {
            errors.add(new ApiFieldError("version", "缺少版本号"));
        }
        var resolved = resolver.forUpdate(request, existing, errors);
        if (request.version() != existing.version()) {
            throw new ApiException(ErrorCode.CONFLICT_VERSION);
        }
        return toPreview(resolved);
    }

    private static ProductPreview toPreview(ProductInputResolver.Resolved resolved) {
        var in = resolved.inputs();
        var pricing = ProductPricing.compute(in);
        return new ProductPreview(pricing.glueGrams(), pricing.glueCost(), pricing.colorpasteCost(),
                pricing.materialCost(), pricing.productLaborFee(), pricing.packagingLaborFee(),
                in.boxLaborFee(), pricing.laborCost(),
                pricing.otherCost(), pricing.totalCost(), pricing.referencePrice(),
                pricing.qty8h(), pricing.qty6h(), in.salePrice(),
                pricing.estimatedProfit(), pricing.estimatedMarginRate(),
                percentText(pricing.estimatedMarginRate()),
                seamBudget(resolved.seamTypeId(), resolved.seamUnitCost(), resolved.seamFee(),
                        pricing.totalCost()));
    }

    /** 缝边剪袋变体预算：未选默认缝边剪袋类型（默认不缝边剪袋）时为 null，不伪造零值。 */
    private static SeamBudgetView seamBudget(Long seamTypeId, BigDecimal seamUnitCost, BigDecimal seamFee,
                                            BigDecimal productTotalCost) {
        if (seamTypeId == null) {
            return null;
        }
        return SeamBudgetView.of(ProductPricing.seamBudget(productTotalCost, seamUnitCost, seamFee));
    }

    /** 利润率展示文本：比例 → 百分比并去掉无意义尾零，如 0.351200 → 35.12%。 */
    private static String percentText(BigDecimal ratio) {
        return DecimalPolicy.ratioToPercent(ratio).stripTrailingZeros().toPlainString() + "%";
    }

    // ---------- 创建 ----------

    @Transactional
    public ProductDetail create(CreateProductRequest request) {
        var errors = new ArrayList<ApiFieldError>();
        String name = request.name() == null ? null : request.name().trim();
        if (name == null || name.isEmpty()) {
            errors.add(new ApiFieldError("name", "名称不能为空"));
        } else if (name.length() > 200) {
            errors.add(new ApiFieldError("name", "名称不能超过 200 字"));
        }
        if (request.imageFileId() != null && !reference.fileExists(request.imageFileId())) {
            errors.add(new ApiFieldError("imageFileId", "图片文件不存在"));
        }
        var resolved = resolver.forCreate(request, errors);
        var in = resolved.inputs();

        var pricing = ProductPricing.compute(in);
        var audit = auditContext.current();
        var productNo = SequenceAllocator.format("P", sequenceAllocator.next("products"));
        var row = new ProductRow(null, productNo, name, request.note(), STATUS_ACTIVE,
                request.imageFileId(), resolved.starLevelId(), resolved.starName(), in.stdMinutes(),
                in.salePrice(), in.weightG(), in.lossRate(),
                in.glueUnitPrice(), pricing.glueGrams(), pricing.glueCost(),
                in.colorpasteUnitPrice(), pricing.colorpasteCost(),
                pricing.qty8h(), pricing.qty6h(), pricing.productLaborFee(),
                resolved.packagingTierId(), resolved.packagingTierName(),
                in.tierStdMinutes(), resolved.packagingCommission(),
                pricing.packagingLaborFee(),
                resolved.seamTypeId(), resolved.seamTypeName(), resolved.seamStdMinutes(),
                resolved.seamUnitCost(), resolved.seamFee(),
                in.boxLaborFee(), in.transportPackingFee(), in.dailySundriesFee(),
                in.rentUtilitiesFee(), in.moldAmortFee(),
                pricing.materialCost(), pricing.laborCost(), pricing.otherCost(),
                pricing.totalCost(), pricing.referencePrice(), 0L);
        long id = repository.insert(row, audit.requestId(), audit.idempotencyKey());
        return toDetail(withId(row, id));
    }

    // ---------- 编辑 ----------

    @Transactional
    public ProductDetail update(long id, UpdateProductRequest request) {
        var existing = requireRow(id);
        var errors = new ArrayList<ApiFieldError>();
        if (request.version() == null) {
            errors.add(new ApiFieldError("version", "缺少版本号"));
        }
        String name = existing.name();
        if (request.name() != null) {
            name = request.name().trim();
            if (name.isEmpty()) {
                errors.add(new ApiFieldError("name", "名称不能为空"));
            } else if (name.length() > 200) {
                errors.add(new ApiFieldError("name", "名称不能超过 200 字"));
            }
        }
        if (request.imageFileId() != null && !reference.fileExists(request.imageFileId())) {
            errors.add(new ApiFieldError("imageFileId", "图片文件不存在"));
        }
        var resolved = resolver.forUpdate(request, existing, errors);
        if (request.version() != existing.version()) {
            throw new ApiException(ErrorCode.CONFLICT_VERSION);
        }
        var in = resolved.inputs();
        var pricing = ProductPricing.compute(in);
        Long imageFileId = request.imageFileId() != null ? request.imageFileId() : existing.imageFileId();
        String note = request.note() != null ? request.note() : existing.note();

        var audit = auditContext.current();
        var updated = new ProductRow(existing.id(), existing.productNo(), name, note, existing.status(),
                imageFileId, resolved.starLevelId(), resolved.starName(), in.stdMinutes(),
                in.salePrice(), in.weightG(), in.lossRate(),
                in.glueUnitPrice(), pricing.glueGrams(), pricing.glueCost(),
                in.colorpasteUnitPrice(), pricing.colorpasteCost(),
                pricing.qty8h(), pricing.qty6h(), pricing.productLaborFee(),
                resolved.packagingTierId(), resolved.packagingTierName(),
                in.tierStdMinutes(), resolved.packagingCommission(),
                pricing.packagingLaborFee(),
                resolved.seamTypeId(), resolved.seamTypeName(), resolved.seamStdMinutes(),
                resolved.seamUnitCost(), resolved.seamFee(),
                in.boxLaborFee(), in.transportPackingFee(), in.dailySundriesFee(),
                in.rentUtilitiesFee(), in.moldAmortFee(),
                pricing.materialCost(), pricing.laborCost(), pricing.otherCost(),
                pricing.totalCost(), pricing.referencePrice(), request.version());
        repository.update(updated, audit.requestId());
        changeLog.record("PRODUCT", existing.id(), existing.productNo(),
                snapshot(existing), snapshot(updated), request.reason(),
                audit.adminUsername(), audit.requestId());
        return toDetail(withVersion(updated, request.version() + 1));
    }

    // ---------- 启停 ----------

    @Transactional
    public ProductDetail changeStatus(long id, boolean active, String reason) {
        var existing = requireRow(id);
        var audit = auditContext.current();
        var updated = new ProductRow(existing.id(), existing.productNo(), existing.name(), existing.note(),
                active ? STATUS_ACTIVE : STATUS_DISABLED,
                existing.imageFileId(), existing.starLevelId(), existing.starName(), existing.starStdMinutes(),
                existing.salePrice(), existing.weightG(), existing.lossRate(),
                existing.glueUnitPrice(), existing.glueGrams(), existing.glueCost(),
                existing.colorpasteUnitPrice(), existing.colorpasteCost(),
                existing.qty8h(), existing.qty6h(), existing.productLaborFee(),
                existing.packagingTierId(), existing.packagingTierName(),
                existing.packagingStdMinutes(), existing.packagingCommission(), existing.packagingLaborFee(),
                existing.seamTypeId(), existing.seamTypeName(), existing.seamStdMinutes(),
                existing.seamUnitCost(), existing.seamFee(),
                existing.boxLaborFee(), existing.transportPackingFee(), existing.dailySundriesFee(),
                existing.rentUtilitiesFee(), existing.moldAmortFee(),
                existing.materialCost(), existing.laborCost(), existing.otherCost(),
                existing.totalCost(), existing.referencePrice(), existing.version());
        repository.update(updated, audit.requestId());
        changeLog.record("PRODUCT", existing.id(), existing.productNo(),
                snapshot(existing), snapshot(updated), reason,
                audit.adminUsername(), audit.requestId());
        return toDetail(withVersion(updated, existing.version() + 1));
    }

    // ---------- 内部工具 ----------

    private ProductRow requireRow(long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "商品不存在"));
    }

    private static LinkedHashMap<String, Object> snapshot(ProductRow row) {
        var map = new LinkedHashMap<String, Object>();
        map.put("name", row.name());
        map.put("status", row.status());
        map.put("starLevelId", row.starLevelId());
        map.put("seamTypeId", row.seamTypeId());
        map.put("seamFee", row.seamFee());
        map.put("salePrice", row.salePrice());
        map.put("weightG", row.weightG());
        map.put("totalCost", row.totalCost());
        map.put("version", row.version());
        return map;
    }

    private static ProductRow withId(ProductRow row, long id) {
        return new ProductRow(id, row.productNo(), row.name(), row.note(), row.status(),
                row.imageFileId(), row.starLevelId(), row.starName(), row.starStdMinutes(),
                row.salePrice(), row.weightG(), row.lossRate(),
                row.glueUnitPrice(), row.glueGrams(), row.glueCost(),
                row.colorpasteUnitPrice(), row.colorpasteCost(),
                row.qty8h(), row.qty6h(), row.productLaborFee(),
                row.packagingTierId(), row.packagingTierName(), row.packagingStdMinutes(),
                row.packagingCommission(), row.packagingLaborFee(),
                row.seamTypeId(), row.seamTypeName(), row.seamStdMinutes(), row.seamUnitCost(), row.seamFee(),
                row.boxLaborFee(), row.transportPackingFee(), row.dailySundriesFee(),
                row.rentUtilitiesFee(), row.moldAmortFee(),
                row.materialCost(), row.laborCost(), row.otherCost(), row.totalCost(), row.referencePrice(),
                row.version());
    }

    private static ProductRow withVersion(ProductRow row, long version) {
        return new ProductRow(row.id(), row.productNo(), row.name(), row.note(), row.status(),
                row.imageFileId(), row.starLevelId(), row.starName(), row.starStdMinutes(),
                row.salePrice(), row.weightG(), row.lossRate(),
                row.glueUnitPrice(), row.glueGrams(), row.glueCost(),
                row.colorpasteUnitPrice(), row.colorpasteCost(),
                row.qty8h(), row.qty6h(), row.productLaborFee(),
                row.packagingTierId(), row.packagingTierName(), row.packagingStdMinutes(),
                row.packagingCommission(), row.packagingLaborFee(),
                row.seamTypeId(), row.seamTypeName(), row.seamStdMinutes(), row.seamUnitCost(), row.seamFee(),
                row.boxLaborFee(), row.transportPackingFee(), row.dailySundriesFee(),
                row.rentUtilitiesFee(), row.moldAmortFee(),
                row.materialCost(), row.laborCost(), row.otherCost(), row.totalCost(), row.referencePrice(),
                version);
    }

    private ProductSummary toSummary(ProductRow row) {
        return new ProductSummary(row.id(), row.productNo(), row.name(), row.status(),
                row.starLevelId(), row.starName(), row.salePrice(), row.totalCost());
    }

    private ProductDetail toDetail(ProductRow row) {
        var lossRatePercent = DecimalPolicy.ratioToPercent(row.lossRate());
        return new ProductDetail(
                row.id(), row.productNo(), row.name(), row.note(), row.status(), row.imageFileId(),
                row.starLevelId(), row.starName(), row.starStdMinutes(),
                row.salePrice(), row.weightG(), lossRatePercent,
                row.glueUnitPrice(), row.glueGrams(), row.glueCost(),
                row.colorpasteUnitPrice(), row.colorpasteCost(),
                row.qty8h(), row.qty6h(), row.productLaborFee(),
                row.packagingTierId(), row.packagingTierName(), row.packagingStdMinutes(),
                row.packagingCommission(), row.packagingLaborFee(),
                row.seamTypeId(), row.seamTypeName(), row.seamStdMinutes(), row.seamUnitCost(), row.seamFee(),
                row.boxLaborFee(), row.transportPackingFee(), row.dailySundriesFee(),
                row.rentUtilitiesFee(), row.moldAmortFee(),
                row.materialCost(), row.laborCost(), row.otherCost(),
                row.totalCost(), row.referencePrice(),
                ProductPricing.estimatedProfit(row.salePrice(), row.totalCost()),
                ProductPricing.estimatedMarginRate(row.salePrice(), row.totalCost()),
                seamBudget(row.seamTypeId(), row.seamUnitCost(), row.seamFee(), row.totalCost()),
                row.version());
    }
}