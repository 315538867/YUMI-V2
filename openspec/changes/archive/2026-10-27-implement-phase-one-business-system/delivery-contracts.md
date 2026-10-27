# 页面、接口与来源交付清单

- 提案版本：1.3；修订日期：2026-10-20；创建者：Codex。
- 业务基线：`a714ede433b37d9d987e7229d2fdead52d723ac1`；本表补齐交付边界，不改业务规则。
- 所有路径均为计划文件；页面实现位置及对应单元测试见 [实施任务](tasks.md)，不是现有可运行接口。

## 1. 使用规则

1. 每行同时交付业务能力、HTTP 命令/查询、页面和测试，不得以一个模块菜单或一个月汇总页面代替全部子入口。
2. `B/`、`T/`、`F/` 与 tasks 别名一致。HTTP 文件列写 `Controller / Query` 类名：分别展开为 `B/<owner>/api/http/<Controller>.java`、`B/<owner>/api/<Query>.java`。Query 只读；HTTP 不调用内部 Repository，不把 Participant、Feed、快照冻结或内部编排命令直接发布为路由。
3. 后端任务列为该能力最后完成节点；相关前序红测试必须先纳入本行行为。每行列出的 Query 和 HTTP 适配器由该节点交付，2.11—2.12、13.7—13.8 是真实集成门禁，不是用替身补交结果。
4. 页面列是用户可见入口，不强制每个操作另开路由；对话框、详情分区可以复用。具体 Vue 文件由前端任务固定，均配 composable/service/contract。
5. 每行 E2E 均验证真实 API 的成功路径、越权拒绝及失败不假成功。命令额外验证版本冲突、幂等与回滚；只读 Query 不要求幂等写入记录。权限编码以相应基线权限表为准。
6. 平台先用一次性运维 CLI 初始化空系统管理员，后续仅通过授权 API 管理账号；不开放匿名注册管理员、不提供默认密码。E2E 也遵循此入口，不直接写数据库种业务数据。

## 2. 面向人的交付矩阵

基线列为 `docs/一期/` 文档编号及章节；E2E 列文件位于 `F/e2e/`。

| ID | owner | 基线 | 页面及完整操作 | HTTP 文件（Controller / Query） | 后端完成任务 | 前端完成任务 | E2E |
|---|---|---|---|---|---|---|---|
| P01 | platform/identity | 06 §8、§11 | 登录退出、当前账号、账号创建/启停；角色编辑和授权经 P02 | IdentityController / IdentityQuery | 2.12 | 14.2、14.20 | navigation.spec.ts、reports-settings.spec.ts |
| P02 | platform/authorization | 06 §8、§11 | 已授权管理员角色维护和账号授权，无员工绑定 | AuthorizationController / AuthorizationAdministrationQuery | 2.12 | 14.20 | reports-settings.spec.ts |
| P03 | platform | 06 §8、§11 | 审计只读检索；后台任务状态、失败来源、重试/放弃；统一文件上传/授权预览下载 | 见下方平台细分文件表 | 2.12 | 14.2、14.22 | navigation.spec.ts、platform-operations.spec.ts |
| C01 | customer | 08 §8、§10 | 客户列表/详情、新增编辑、启停、历史变化与收货资料 | CustomerController / CustomerQuery | 4.4 | 14.4 | customers.spec.ts |
| D01 | product | 09 §11、§14 | 商品列表/详情/录入、停用、标准/自定义方案、预算；保留标准售价，无商品目标工序 | ProductController / ProductQuery | 5.8 | 14.6 | products.spec.ts |
| D02 | product | 09 §11、§14 | 胶水损耗/价格、色浆等录入默认值、制作星级和工序标准时间选项 | ProductEntryConfigurationController / ProductEntryConfigurationQuery | 5.8 | 14.6 | products.spec.ts |
| W01 | workforce | 10 §9、§11 | 员工资料、离职返聘、工种资格、就业历史；小组成员及成员历史 | WorkforceController / WorkforceQuery | 6.6 | 14.8 | workforce.spec.ts |
| W02 | workforce | 10 §9、§11 | 效率标准、每日有效时间及活动引用查询；受活动引用限制的变更明确拒绝 | WorkforceStandardsController / WorkforceStandardsQuery | 6.6 | 14.8 | workforce.spec.ts |
| N01 | production | 11 §12 | 需求缺口、日期/周排班、个人/组负载、列表详情、安排调整取消和一次最终核验；OTHER_TASK 例外 | ProductionTaskController / ProductionTaskQuery | 7.16 | 14.10 | production.spec.ts |
| N02 | production | 11 §12、12 §13 | 已核验任务的质量/处置更正、报废责任独立更正、生产事实更正及缺陷原因维护；售后池初次处置只能经 A01 编排，不提供独立生产处置网页命令；移交后限制按基线执行 | ProductionQualityController / ProductionQualityQuery | 9.8 | 14.10 | production.spec.ts |
| N03 | production | 11 §12 | 来源详情、转线下记录及完整订单生产链路，只读追溯不修改已退出实物 | ProductionTraceController / ProductionQuery | 7.16 | 14.10 | production.spec.ts |
| O01 | orders/trade | 12 §11 | 订单列表/详情、确认、正式变更、已有订单接管；无服务端草稿 | OrderController / OrderQuery | 8.10 | 14.12 | orders.spec.ts |
| O02 | orders/fulfillment | 12 §11 | 发货及更正、交付履约状态；收退款入口组合 F04，不另造 orders 现金命令 | OrderDeliveryController / OrderFulfillmentQuery | 8.10 | 14.12 | orders.spec.ts |
| A01 | orders/aftersales | 12 §13 | 案例受理/查询、义务、最近交付退回、原样退还、售后池处置 disposeReturnedProduct（含直接报废并选择去向）、修复/补发路线与取消、结案 | AfterSalesController / AfterSalesQuery | 9.10 | 14.14 | aftersales.spec.ts |
| A02 | orders/aftersales | 12 §13 | 售后费用确认/更正；收款退款赔付入口组合 F04；费用不等于现金 | AfterSalesCostController / AfterSalesCostQuery | 9.10 | 14.14 | aftersales.spec.ts |
| F01 | finance/payroll | 13 §9、§11 | 工资期间、参考运行、逐项成本、逐人核定、已核定只读复核、人工调整；付款组合 F04 | PayrollController / PayrollQuery | 10.12 | 14.16 | payroll.spec.ts |
| F02 | finance/payroll | 13 §9、§11 | 设置中月胶水减免版本/生效月；员工自然月来源/减免明细，不按工资期间重置 | PayrollGlueController / GlueMonthReferenceQuery | 10.12 | 14.16、14.20 | payroll.spec.ts、reports-settings.spec.ts |
| F03 | finance/costledger | 13 §6、§9、§11 | 成本明细/汇总、归集失败定位/重试、明确分摊、精确反向及替代更正 | CostLedgerController / CostLedgerQuery | 11.6 | 14.18、14.24 | finance.spec.ts、cost-operations.spec.ts |
| F04 | finance/cash | 13 §9、§11 | 订单及售后收退款/赔付、逐人多人部分工资付款；凭证随本条；资格由对应内部参与者校验 | CashController / CashQuery | 12.10 | 14.12、14.14、14.16 | orders.spec.ts、aftersales.spec.ts、payroll.spec.ts |
| F05 | finance/cash | 13 §9、§11 | 日常经营收支、分类/业务日期/可选计费用；流水、来源分配和凭证详情 | DailyCashController / CashQuery | 12.10 | 14.18 | finance.spec.ts |
| F06 | finance/cash | 13 §9、§11 | 原员工支付分配的真实部分退回；错误现金整笔冲销，受退回关联上限保护 | CashCorrectionController / CashQuery | 12.10 | 14.18 | finance.spec.ts |
| R01 | reporting | 14 §6、§8 | 经营总览/月汇总及订单、生产、售后、工资、成本、现金主题，版本/水位/延迟和不完整提示 | ReportingController / ReportingQuery | 13.6 | 14.20、14.26 | reports-settings.spec.ts、report-operations.spec.ts |
| R02 | reporting | 14 §6、§8 | 授权管理员发起来源对账、查看差异、影子重建及进度；失败保留旧投影 | ReportingAdministrationController / ReportingAdministrationQuery | 13.6 | 14.26 | report-operations.spec.ts |

P03 的精确文件路径与 owner：

| owner | HTTP Controller | Query | 管理动作 |
|---|---|---|---|
| platform/audit | `B/platform/audit/api/http/AuditController.java` | `B/platform/audit/api/AuditQuery.java` | 只读检索，不开放修改审计接口 |
| platform/aftercommit | `B/platform/aftercommit/api/http/AfterCommitTaskController.java` | `B/platform/aftercommit/api/AfterCommitTaskQuery.java` | 授权 retry/abandon；沿用原任务身份及租约保护 |
| platform/file | `B/platform/file/api/http/FileController.java` | `B/platform/file/api/FileQuery.java` | 临时上传/授权读取；正式引用随业务事务，不设独立凭证池 |

页面与命令归属不同不代表跨模块直接调用内部服务。例如订单页调用 F04 的 cash HTTP；cash 调用 orders 的支付参与者，而不是 orders Controller 调 finance 再回调自身。胶水设置虽然放设置菜单，仍调用 F02 的 payroll HTTP。

### 售后池处置事务边界

- 用户只向 `B/orders/aftersales/api/http/AfterSalesController.java` 提交 `disposeReturnedProduct`，由 orders 的公开 `AfterSalesApplicationService` 委派 `AfterSalesReturnDispositionService` 编排；页面不能先调用生产处置，再补记售后记录。
- orders 先鉴权、幂等并按基线锁定售后案例、有效义务及退回接收明细，校验版本、归属和可处置数量，再调用 `ProductionAfterSalesReturnParticipant.prepareDisposition`。
- orders 保存 `after_sales_return_disposition_facts` 及关联引用；production 通过同一事务的 `commitDisposition` 保存生产侧处置、责任、唯一退出及适用的消费/移交。双方关联同一命令和处置结果，任一写入失败全部回滚，不开启独立提交事务。
- 原样退还仍由售后交付命令同事务记录实际交付；义务取消调用同一内部参与能力处理各分支，不能通过 HTTP 再串一个独立处置事务。
- 生产侧写入事实不意味着 production 拥有售后处置的网页命令。N02 保留基线允许的独立责任更正，不允许通过该入口重新处置退回池或恢复已移交数量。
- 任务 9.7—9.8 同时验证 orders 写入失败、production 写入失败、越权/错误义务引用、并发数量上限和成功幂等重试；14.13—14.14 验证页面只提交 orders 命令。计划校验器只检查清单中的该命令归属，真实事务行为仍须实施后验证。

## 3. 内部来源生产者门禁

以下均不开放 HTTP；提供者任务必须在消费者开始前完成，并在提供者前序红测试中覆盖稳定分页、重复读取和完整更正批次。游标归各 Feed 所有，reporting 不输出公共游标给业务模块。

| 来源 | 提供者完成任务 | 首个消费者测试任务 |
|---|---|---|
| CustomerReportingFeed | 4.4 | 13.1 |
| ProductReportingFeed | 5.8 | 13.1 |
| WorkforceReportingFeed | 6.6 | 13.1 |
| ProductionReportingFeed | 7.16 | 13.1 |
| OrderReportingFeed | 8.10 | 13.1 |
| AfterSalesReportingFeed | 9.10 | 13.1 |
| PayrollReportingFeed | 10.12 | 13.1 |
| CostReportingFeed | 11.6 | 13.1 |
| CashReportingFeed | 12.10 | 13.1 |
| Production 劳动/责任/材料完整命令批次 | 7.12 | 10.1、11.1 |
| 退回池责任来源联合类型 | 9.8 | 10.1 |
| OrderAfterSalesCostSourceFeed | 9.10 | 11.3 |
| PayrollPaymentParticipant | 10.12 | 12.1 |
| OrderPaymentParticipant 及售后有效义务资格 | 8.8、9.10 | 12.1 |
| 同事务经营费用入账及反向能力 | 11.6 | 12.3、12.9 |

订单/生产/人员事务参与接口按任务 6—9 逐层构建。内部参与测试可以用调用方事务验证资格与回滚，但不能把它叫作尚未实现的下游真实付款或订单闭环测试。

## 4. 验收门禁

- 1.10：纯 HTTP/批次值契约；不声称认证、持久幂等或数据库来源已经实现。
- 2.12：真实平台 HTTP 与数据库绑定；后续业务阶段依赖此门禁。
- 各业务 owner 完成节点：本表命令、Query、HTTP 和 Feed 都交付；不得留到页面阶段补核心来源。
- 13.8：所有业务 HTTP 清单逐条真实测试，关键金额精度在实际业务接口验证，不在没有金额命令的平台测试中伪造。
- 14.26：所有业务页与后台管理入口均有可操作页面；不是只有延迟文字。
- 15.8：全量验收与基线逐条核对，保存实际测试证据；本提案当前仅定义这些条件。
