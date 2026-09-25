package com.yumi.orders.order.internal;

import com.yumi.calculation.DecimalPolicy;
import com.yumi.calculation.order.OrderPricing;
import com.yumi.calculation.product.ProductPricing;
import com.yumi.orders.order.OrderItemRequest;
import com.yumi.shared.error.ApiFieldError;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单明细解析（任务 3.2/3.7 共用）：草稿明细与订单变更新增行必须走同一入口，
 * 只做引用选择、默认值、校验与计价调用，不含事务、编号或状态判断。
 * 商品识别信息与单件成本一律服务端读取，客户端提交的派生金额被忽略。
 */
@Component
public class OrderItemResolver {

    static final String PRODUCT_ACTIVE = "ACTIVE";

    private final OrderReference reference;

    public OrderItemResolver(OrderReference reference) {
        this.reference = reference;
    }

    /** 解析后的明细：待落库的行 + 计价结果 + 商品参考数据（快照用）。 */
    public record Resolved(OrderItemRow row, OrderPricing.ItemResult amounts, OrderReference.Product product) {
    }

    /**
     * 批量解析新明细，行号从 {@code firstLineNo} 起连续编号。
     * {@code requirePositiveQuantity} 为 true 时数量必须大于 0（订单草稿）；
     * 订单变更允许为 0（移除行按数量归零处理）。
     */
    public List<Resolved> resolve(List<OrderItemRequest> requests, int firstLineNo,
                                  boolean requirePositiveQuantity, List<ApiFieldError> errors) {
        if (requests == null || requests.isEmpty()) {
            errors.add(new ApiFieldError("items", "至少一条明细"));
            return List.of();
        }
        var resolved = new ArrayList<Resolved>();
        for (int index = 0; index < requests.size(); index++) {
            var item = requests.get(index);
            var prefix = "items[" + index + "]";
            var row = resolveOne(item, firstLineNo + index, prefix, null, requirePositiveQuantity, errors);
            if (row != null) {
                resolved.add(row);
            }
        }
        return resolved;
    }

    /**
     * 解析单条明细：{@code current} 非空表示在现有明细上做缺省合并（订单变更的改单），
     * 为空表示新增。返回 null 表示校验失败已记入 {@code errors}。
     */
    public Resolved resolveOne(OrderItemRequest item, int lineNo, String prefix, OrderItemRow current,
                               boolean requirePositiveQuantity, List<ApiFieldError> errors) {
        var productId = item.productId() != null ? item.productId()
                : current == null ? null : current.productId();
        if (productId == null) {
            errors.add(new ApiFieldError(prefix + ".productId", "商品必填"));
            return null;
        }
        var product = reference.product(productId).orElse(null);
        if (product == null) {
            errors.add(new ApiFieldError(prefix + ".productId", "商品不存在"));
            return null;
        }
        if (!PRODUCT_ACTIVE.equals(product.status())) {
            errors.add(new ApiFieldError(prefix + ".productId", "商品已停用"));
            return null;
        }
        int quantity = item.quantity() != null ? item.quantity()
                : current == null ? 0 : current.quantity();
        if (requirePositiveQuantity && quantity < 1) {
            errors.add(new ApiFieldError(prefix + ".quantity", "数量必须大于 0"));
        } else if (quantity < 0) {
            errors.add(new ApiFieldError(prefix + ".quantity", "数量不能为负数"));
        }
        int seamQuantity = item.seamQuantity() != null ? item.seamQuantity()
                : current == null ? 0 : current.seamQuantity();
        if (seamQuantity < 0 || seamQuantity > quantity) {
            errors.add(new ApiFieldError(prefix + ".seamQuantity", "缝边数量必须在 0 与明细数量之间"));
        }
        BigDecimal unitPrice = item.unitPrice() != null ? item.unitPrice()
                : current == null ? product.salePrice() : current.unitPrice();
        if (unitPrice.signum() < 0) {
            errors.add(new ApiFieldError(prefix + ".unitPrice", "成交价不能为负数"));
        }
        Long requestedSeamTypeId = item.seamTypeId() != null ? item.seamTypeId()
                : current == null ? null : current.seamTypeId();
        return resolveSeam(product, item, prefix, lineNo, quantity, seamQuantity,
                DecimalPolicy.money(unitPrice), requestedSeamTypeId, current, errors);
    }

    private Resolved resolveSeam(OrderReference.Product product, OrderItemRequest item, String prefix, int lineNo,
                                 int quantity, int seamQuantity, BigDecimal unitPrice, Long requestedSeamTypeId,
                                 OrderItemRow current, List<ApiFieldError> errors) {
        Long seamTypeId = null;
        String seamTypeName = null;
        BigDecimal seamUnitCost = BigDecimal.ZERO;
        BigDecimal seamFee = BigDecimal.ZERO;
        if (seamQuantity > 0) {
            var typeId = requestedSeamTypeId != null ? requestedSeamTypeId : product.defaultSeamTypeId();
            if (typeId == null) {
                errors.add(new ApiFieldError(prefix + ".seamTypeId", "缝边数量大于 0 时必须选择缝边种类"));
            } else {
                var type = reference.seamType(typeId).orElse(null);
                if (type == null) {
                    errors.add(new ApiFieldError(prefix + ".seamTypeId", "缝边种类不存在"));
                } else {
                    seamTypeId = type.id();
                    seamTypeName = type.name();
                    seamUnitCost = ProductPricing.seamUnitCost(type.stdMinutes(), reference.hourlyWage());
                }
            }
            BigDecimal fee;
            if (item.seamFee() != null) {
                fee = item.seamFee();
            } else if (current != null && current.seamQuantity() > 0) {
                fee = current.seamFee();
            } else {
                fee = product.defaultSeamFee();
            }
            seamFee = DecimalPolicy.money(fee == null ? BigDecimal.ZERO : fee);
            if (seamFee.signum() < 0) {
                errors.add(new ApiFieldError(prefix + ".seamFee", "缝边收费不能为负数"));
            }
        }
        var amounts = OrderPricing.item(new OrderPricing.ItemInputs(unitPrice, quantity, seamFee, seamQuantity,
                seamUnitCost, product.totalCost()));
        String note = item.note() != null ? item.note() : current == null ? null : current.note();
        var row = new OrderItemRow(current == null ? null : current.id(),
                current == null ? 0L : current.orderId(), lineNo, product.id(), product.productNo(),
                product.name(), quantity, seamQuantity, unitPrice, amounts.goodsAmount(),
                seamTypeId, seamTypeName, seamUnitCost, seamFee, amounts.seamAmount(),
                DecimalPolicy.money(product.totalCost()), amounts.goodsCostAmount(), amounts.seamCostAmount(),
                note, current == null ? 0L : current.version());
        return new Resolved(row, amounts, product);
    }
}
