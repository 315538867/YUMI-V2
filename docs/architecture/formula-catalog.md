# 公式目录（集中计算基线）

日期：2026-09-24  
修改人：chen  
依据：`docs/architecture/formula-management-design.md` 第 9 节；本目录随实现与测试一同更新，**不是运行配置**，后端不读取本文件。

## 1. 使用方式

- 每行业务公式有稳定标识 `FP-<分类>-<序号>`，用于代码注释、测试名和评审追踪。
- 新增公式必须先在此登记再实现；实现位置只能落在 `com.yumi.calculation` 对应分类。
- “舍入节点”列写明该步骤是否截断以及精度；未列出的中间步骤保持 `BigDecimal` 原始精度，不提前截断。

## 2. 实施时公式盘点（2026-09-24，任务 2.13）

盘点范围为后端 `src/main`、前端 `src` 与 Flyway 迁移脚本。

| 位置 | 现状 | 判定 | 处置 |
| --- | --- | --- | --- |
| `backend/src/main/java/com/yumi/catalog/product/internal/ProductPricing.java` | 商品计价全链：损耗率换算、胶水/色浆、标准产量、星级人工、包装、缝边、三项成本、总成本、参考售价、利润与利润率、金额精度工具 | 业务公式（FP-PROD-01..17、19） | 迁入 `calculation/product` 与 `calculation/DecimalPolicy`，删除旧实现 |
| `backend/src/main/java/com/yumi/catalog/product/ProductService.java:368` | `lossRate × 100` 回显百分比 | 业务公式（FP-PROD-18） | 迁入 `calculation`，`ProductService` 改为调用 |
| `backend/src/main/java/com/yumi/catalog/product/internal/CatalogReference.java:42` | `moneySetting()` 读库后转 scale4 | 边界换算（读库口径） | 保留在 `catalog`，改调 `DecimalPolicy.money` |
| `backend/src/main/java/com/yumi/catalog/settings/SettingsService.java:96,336,341` | 设置值写入规范化与输出序列化（scale4/scale6） | 边界规范化，无算术链 | 保留在 `catalog`，改调 `DecimalPolicy`；见第 6 节偏差 |
| `backend/src/main/java/com/yumi/catalog/product/internal/ProductInputResolver.java` | 缝边时长统一转 scale3、四项费用与金额入参按 `DecimalPolicy` 归一 | 边界规范化，无算术链 | 保留在 `catalog`；工时精度按设计第 5 节“不按金额规则截断”，故不并入 `DecimalPolicy` |
| `frontend/src/pages/catalog/ProductsPage.tsx:30-128` | `computePreview` 与 `m4`/`m6` 本地公式副本 | 公式副本 | 任务 2.17 删除，改调服务端试算 |
| `backend/src/main/resources/db/migration/V1__yumi_v2_schema.sql`（2026-09-25 由原 V1–V14 压缩的单一基线） | 仅建表、约束、静态数据与全局参数默认值，无计算列或计算表达式 | 非公式 | 不迁移；`database-design.md` 已禁止在 SQL 重写成本/利润链 |

业务约束（校验、引用选择、快照合并、事务、锁、幂等）不属于公式，仍留在业务模块，见第 5 节。

**本节为 2.13 当时的盘点快照，保留不改写**；其中缝边相关行（`ProductInputResolver` 的缝边时长 scale3）已随 2.23 移除。2026-09-24 起商品缝边口径为「只提供默认值 + 缝边剪袋变体预算」（§3 的 FP-PROD-20/21），`ProductInputResolver` 现只做引用选择与金额归一，不含缝边时长换算；原 `V6__packaging_commission_default.sql`（2.23 追加的全局默认提成）已随 2026-09-25 的单一基线压缩并入 `V1__yumi_v2_schema.sql`。

## 3. 商品公式目录

公式语义与常量见 `formula-management-design.md` 第 5 节；**2026-09-25 起**时薪、工作日小时数、制品有效工时率与目标利润率均为全局设置（`hourly_wage`、`workday_hours`、`making_effective_hour_rate`、`target_margin_rate`），商品侧不再有硬编码的业务常量。

| 标识 | 名称 | 输入与单位 | 表达式 | 舍入节点 | 结果含义 |
| --- | --- | --- | --- | --- | --- |
| FP-PROD-01 | 损耗率换算 | 百分比文本 % | `percent / 100` | scale6 HALF_UP | 内部损耗比例 |
| FP-PROD-02 | 胶水克重 | 重量 g、损耗比例 | `weightG × (1 + lossRate)` | 取整 HALF_UP | 单件耗用克重 |
| FP-PROD-03 | 胶水成本 | 克重、胶水单价 | `glueGrams × glueUnitPrice` | scale4 HALF_UP | 单件胶水成本 |
| FP-PROD-04 | 色浆成本 | 克重、色浆单价 | `glueGrams × colorpasteUnitPrice` | scale4 HALF_UP | 单件色浆成本 |
| FP-PROD-05 | 工作日标准数量 | 星级标准时长 min、工作日小时数 | `floor(工作日小时数 × 60 / stdMinutes)` | 向下取整 | 整个工作日的满速产量（仅参考） |
| FP-PROD-06 | 有效工时产量 | 星级标准时长 min、工作日小时数、制品有效工时率 | `floor(工作日小时数 × 60 × 制品有效工时率 / stdMinutes)` | 向下取整 | 制品有效工时内的标准产量 |
| FP-PROD-07 | 星级单件人工费 | 时薪、工作日小时数、有效工时产量 | `产量 = 0 → 0`，否则 `(时薪 × 工作日小时数) / 产量` | scale4 HALF_UP | 单件制品人工 |
| FP-PROD-08 | 包装人工费 | 档位整数标准分钟、时薪、**商品包装提成** | `tierStdMinutes × (时薪 / 60) + packagingCommission`；无档位 → 0 | scale4 HALF_UP | 单件包装人工 |
| FP-PROD-11 | 材料成本 | 胶水成本、色浆成本 | `glueCost + colorpasteCost` | scale4 HALF_UP | 材料分项 |
| FP-PROD-12 | 人工成本 | 星级人工、包装人工、装箱人工 | `productLaborFee + packagingLaborFee + boxLaborFee` | scale4 HALF_UP | 人工分项 |
| FP-PROD-13 | 其他成本 | 运输包装、日常杂费、房租水电、模具摊销 | 四项相加 | scale4 HALF_UP | 其他分项 |
| FP-PROD-14 | 单件总成本 | 三项成本 | `materialCost + laborCost + otherCost` | scale4 HALF_UP | 基础单件成本 |
| FP-PROD-15 | 参考售价 | 单件总成本、目标利润率 | `totalCost / (1 − 目标利润率)` | scale4 HALF_UP | 按目标利润率反推售价 |
| FP-PROD-16 | 预计利润 | 成交价、单件总成本 | `salePrice − totalCost` | scale4 HALF_UP | 读时派生，允许为负 |
| FP-PROD-17 | 预计利润率 | 成交价、单件总成本 | `salePrice = 0 → 0`，否则 `(salePrice − totalCost) / salePrice` | scale6 HALF_UP | 读时派生比例 |
| FP-PROD-18 | 损耗率回显百分比 | 内部损耗比例 | `lossRate × 100` | scale6 HALF_UP | 接口回显百分比文本 |
| FP-PROD-19 | 金额精度工具 | 任意 BigDecimal | 原值转 scale4 | scale4 HALF_UP | 金额统一口径（`DecimalPolicy.money`） |
| FP-PROD-20 | 缝边剪袋变体总成本 | 不缝边剪袋总成本、单件缝边人工成本（= 缝边标准分钟 × 时薪 ÷ 60） | `totalCost + seamUnitCost`；缺省按 0 | scale4 HALF_UP | 单件缝边剪袋变体成本（不写入商品快照） |
| FP-PROD-21 | 缝边剪袋变体参考售价 | 缝边剪袋变体总成本 | `seamTotalCost / 0.7` | scale4 HALF_UP | 缝边剪袋变体的反推售价 |

上述标识在实现中的位置与测试编号见第 4 节。

## 4. 订单公式目录

依据 `docs/architecture/order-module-design.md` §4（2026-09-24 评审通过，用户确认「优惠作用于整单应收」）。实现在 `calculation/order/OrderPricing`，金额一律 scale4 HALF_UP，只对乘积舍入（入参由边界归一为 scale4）。

| 标识 | 名称 | 输入与单位 | 表达式 | 舍入节点 | 结果含义 |
| --- | --- | --- | --- | --- | --- |
| FP-ORDER-01 | 明细商品金额 | 成交单价（元/件）、订购数量 | `unitPrice × quantity` | scale4 HALF_UP | 单条明细商品金额 |
| FP-ORDER-02 | 明细缝边收费 | 缝边收费单价（元/件）、缝边数量 | `seamFee × seamQuantity` | scale4 HALF_UP | 单条明细缝边应收 |
| FP-ORDER-03 | 明细商品成本 | 商品单件成本（不缝边剪袋口径）、订购数量 | `unitCost × quantity` | scale4 HALF_UP | 单条明细商品成本 |
| FP-ORDER-04 | 明细缝边成本 | 单件缝边人工成本（= 缝边标准分钟 × 时薪 ÷ 60，允许 0）、缝边数量 | `seamUnitCost × seamQuantity` | scale4 HALF_UP | 单条明细缝边成本 |
| FP-ORDER-05 | 订单商品金额 | 各明细商品金额 | Σ 明细商品金额 | scale4 HALF_UP | 订单商品应收 |
| FP-ORDER-06 | 订单缝边收费 | 各明细缝边收费 | Σ 明细缝边收费 | scale4 HALF_UP | 订单缝边应收 |
| FP-ORDER-07 | 订单应收 | 商品金额、缝边收费、整单优惠 | `goodsAmount + seamAmount − discountAmount` | scale4 HALF_UP | 客户应付；优惠只作用于应收 |
| FP-ORDER-08 | 订单总成本 | 各明细商品成本与缝边成本 | Σ（明细商品成本 + 明细缝边成本） | scale4 HALF_UP | 商品成本与缝边成本分列可见 |
| FP-ORDER-09 | 订单利润 | 订单应收、订单总成本 | `receivableAmount − costAmount` | scale4 HALF_UP | 允许为负 |

口径约束：整单优惠必须非负且不超过「商品金额 + 缝边收费」（越界由计算模块拒绝并映射为字段级 400）；商品成本取**下单/编辑当时**的商品 `total_cost` 快照，确认时再冻结进明细快照；缝边数量为 0 视为不缝边剪袋，缝边单价与成本按 0 传入。

上述标识在实现中的位置与测试编号见第 5 节。


**编号不复用**：FP-PROD-09（缝边单件成本）与 FP-PROD-10（缝边参考收费）原属商品缝边，2026-09-24 起缝边**决策权移到订单**（商品只提供「默认缝边剪袋类型 + 缝边价格」默认值，不含缝边数量与成本），其编号保留不再使用；缝边的成本与收费公式随订单实现登记到 `calculation/order` 分类。商品包装人工费（FP-PROD-08）的提成来源由“档位提成”改为“商品包装提成（默认取全局 `packaging_commission_default`，商品可改）”。

**FP-PROD-20/21 与 09/10 的区别**：09/10 是商品手填缝边分钟派生的**单件缝边成本与收费**（已废弃）；20/21 是商品侧**缝边剪袋变体预算**——用不缝边剪袋总成本加「单件缝边人工成本（缝边标准分钟 × 时薪 ÷ 60）」得到变体成本，只在试算与详情单列展示，不进入商品快照、不参与商品自身成本与利润。

## 5. 代码位置与测试编号

| 标识 | 目标代码位置 | 测试编号 |
| --- | --- | --- |
| FP-PROD-01 | `calculation/DecimalPolicy.percentToRatio` | `ProductPricingBaselineTest#lossRateConversionUsesScale6HalfUp` |
| FP-PROD-02 | `calculation/product/ProductPricing.compute` | `ProductPricingBaselineTest#glueGramsRoundsHalfUpAtPointFiveBoundary` |
| FP-PROD-03、04 | `calculation/product/ProductPricing.compute` | `ProductPricingBaselineTest#highPrecisionUnitPricesRoundHalfUpAtMoneyNode` |
| FP-PROD-05、06、07 | `calculation/product/ProductPricing.compute` | `ProductPricingBaselineTest#nonDivisibleStandardMinutesFloorBeforeLaborFee`、`ProductPricingTest#zeroQty6ProducesZeroLaborInsteadOfDivisionError` |
| FP-PROD-08 | `calculation/product/ProductPricing.compute` | `ProductPricingBaselineTest#fractionalMinutesDriveSeamAndPackagingFees`、`ProductPricingTest#packagingTierDrivesPackagingLaborFee` |
| FP-PROD-09、10 | `calculation/product/ProductPricing.compute` | `ProductPricingBaselineTest#fractionalMinutesDriveSeamAndPackagingFees` |
| FP-PROD-11、12、13、14 | `calculation/product/ProductPricing.compute` | `ProductPricingBaselineTest#otherCostsSumIntoTotalCost`、`#highPrecisionUnitPricesRoundHalfUpAtMoneyNode` |
| FP-PROD-15 | `calculation/product/ProductPricing.compute` | `ProductPricingBaselineTest#otherCostsSumIntoTotalCost` |
| FP-PROD-16、17 | `calculation/product/ProductPricing.estimatedProfit/estimatedMarginRate` | `ProductPricingBaselineTest#zeroSalePriceYieldsZeroMarginAndNegativeProfit`、`ProductPricingTest#zeroSalePriceYieldsZeroMarginWithoutDivisionError` |
| FP-PROD-18 | `calculation/DecimalPolicy.ratioToPercent` | `ProductPricingTest#pinnedExampleMatchesEveryFormula`（回显 `20.000000`） |
| FP-PROD-19 | `calculation/DecimalPolicy.money` | `ProductPricingBaselineTest#highPrecisionUnitPricesRoundHalfUpAtMoneyNode` |
| FP-PROD-20、21 | `calculation/product/ProductPricing.seamBudget` | `ProductPricingBaselineTest#seamBudgetAddsSeamUnitCostWithoutTouchingProductCost`、`#seamBudgetTreatsMissingSeamTypeCostAndFeeAsZero`、`ProductSeamDefaultApiTest#createStoresSeamDefaultAndReturnsBothBudgets` |
| FP-ORDER-01、02、03、04 | `calculation/order/OrderPricing.item` | `OrderPricingBaselineTest#itemAmountsMultiplyAndRoundHalfUpAtMoneyNode`、`#itemAmountRoundsProductNotMultiplicand` |
| FP-ORDER-05、06、07、08、09 | `calculation/order/OrderPricing.totals` | `OrderPricingBaselineTest#orderTotalsSubtractDiscountFromReceivableOnly`、`#orderTotalsSumMultipleItemsAndAllowNegativeProfit`、`#orderTotalsAllowZeroReceivableButRejectOutOfRangeDiscount`、`#emptyOrderHasZeroTotals`、`OrderApiTest#createsDraftWithMonotonicNumberAndServerSideAmounts` |

端到端钉死算例（HTTP 全链）：`ProductPricingTest#pinnedExampleMatchesEveryFormula`、`#weightChangeCascadesThroughMaterialToTotalAndReferencePrice`、`#starChangeRecomputesQuantitiesAndLabor`、`#refreshMaterialPricesFlagReReadsGlobalUnitPrices`。

## 6. 仍属业务模块的内容

输入校验与字段错误、星级/档位/材料价格的引用选择与快照读取、事务边界、并发锁、事实入账、幂等与结果持久化、变更日志。例如“可执行数量如何计算”属公式，“核验时锁定哪些记录、是否允许扣减、如何防重复核验”属生产领域服务。

## 7. 未建设业务的接入位

以下分类随对应业务实现加入 `calculation`，**不提前创建空实现**：

- `calculation/order`：订单金额与收退款派生值。已确认口径——订单结清净额 = 累计订单收款 − 累计订单变更退款；订单待退款 = `max(累计订单收款 − 当前有效应收 − 累计订单变更退款, 0)`；累计实际净收 = 累计订单收款 − 累计订单变更退款 − 累计售后退款。
- `calculation/inventory`：批次余额与领用可执行数量。
- `calculation/production`：标准工作量与核验数量余额。
- `calculation/payroll`、`calculation/finance`：计薪与财务汇总，业务确认后接入。

## 8. 盘点发现的偏差

1. **设置写入对超精度小数报 500**：`SettingsService.java:96` 用无 `RoundingMode` 的 `setScale(4)`（比例项为 `setScale(6)`），该重载按 `UNNECESSARY` 处理，需要进位时抛 `ArithmeticException`，被 `GlobalExceptionHandler` 的兜底分支转成 500。列定义为 `DECIMAL(19,6)`（如 `glue_unit_price`），管理员提交 `"0.012345"` 这类六位单价即触发，与 `formula-management-design.md` 第 5 节“金额 4 位 HALF_UP、比例 6 位 HALF_UP”不一致。任务 2.14 集中精度策略时改为 `DecimalPolicy`，先补 RED 用例再修。
2. **前端只在展示时舍入，后端每个公式节点都舍入**：后端在 FP-PROD-03/04/11/12/14 各节点截断到 scale4；前端保留全精度、仅在输出时 `m4`。凡中间值超过四位小数，前端展示的分项与合计就可能与后端相差 0.0001。可达例：材料侧后端 FP-PROD-03/04 得 `0.1235`/`0.0346`、FP-PROD-11 得 `0.1581`，前端显示 `m4(0.12345+0.034567) = 0.1580`。任务 2.17 删除本地副本后该分歧消失。（原「档位 `std_minutes` 允许三位小数」的例子已失效：2026-09-25 起三类标准分钟统一为整数。）
