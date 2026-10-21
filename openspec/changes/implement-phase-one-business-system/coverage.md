# 一期方案覆盖及验收映射

- 版本：1.3；2026-10-19；创建者：Codex。
- 源方案版本：`a714ede433b37d9d987e7229d2fdead52d723ac1`；全部 `docs/一期/` 文档在该提交冻结。
- 状态：155 项实施任务于 2026-10-21 全部完成，验证记录见第 2 节；第 1 节保留计划映射与依赖审查原文。

修订记录：2026-10-20，Codex 将提案更新为 1.1；修复工资付款归属及任务依赖，补齐其他任务来源和核验例外。基线提交不变，业务实施未开始。

修订记录：2026-10-20，Codex 将提案更新为 1.2；统一修复契约与真实集成的阶段依赖，补齐用户页面、管理恢复入口和来源生产者门禁；新增交付清单和可重复的计划校验。业务基线不变，尚未实施。

修订记录：2026-10-20，Codex 将提案更新为 1.3；售后池初次处置统一由 orders 编排，production 仅在同一事务参与；同步双端回滚、页面单一命令及归属校验，业务基线不变，未实施。

修订记录：2026-10-21，chen 在版本 1.3 下追加实施与验证记录、已知实现差异，回写全量门禁与手工演练证据。生产任务独立“调整”在本 change 内按 §7.2 补齐公开命令、HTTP、页面与测试。

## 源文档覆盖

| 文档 | 本提案覆盖位置 |
|---|---|
| 00 文档总览 | proposal 元数据、全量范围及本表 |
| 01 文档编写章程 | 工件职责、事实所有权、可验证场景与未实施声明 |
| 02 一期业务范围与需求基线 | 全部能力及 proposal 非目标 |
| 03 系统总体架构与模块依赖 | system-foundation、management-ui、design D1/D2 |
| 04 公共业务与技术规则 | system-foundation、business-calculation、各模块更正/精度约束 |
| 05 跨模块接口与事务规则 | system-foundation、各事务参与能力、design D2/D4 |
| 06 平台公共能力模块 | platform-security；任务 2.9—2.12、14.21—14.22 覆盖账号、角色、安全引导及审计/任务恢复 |
| 07 计算中心模块 | business-calculation |
| 08 客户模块 | customer-management、management-ui |
| 09 商品模块 | product-management、business-calculation、management-ui |
| 10 人员与小组模块 | workforce-management、management-ui |
| 11 生产作业模块 | production-operations、after-sales-management、management-ui |
| 12 订单模块 | order-management、after-sales-management、cash-management、management-ui |
| 13 财务结算模块 | payroll-settlement、cost-ledger、cash-management、management-ui；任务 10.9—10.12 覆盖调整及内部支付参与，12.1—12.2 和 14.15—14.16 覆盖实际付款与页面 |
| 14 报表模块 | operating-reports、cash-management、management-ui |
| 15 一期整体验收标准 | 全部规范场景及 tasks 第15组 |

## 要求到任务

每条要求对应具体红/绿任务，而不再仅指向整个能力组。所有面向用户的行为同时受 13.7—13.8 HTTP 门禁及第 15 组闭环验收约束；具体页面、接口 owner 和来源提供者见 [交付清单](delivery-contracts.md)。新增或修改要求必须同步本表及交付清单。

| 要求编号与标题 | 能力规范 | 实施/验证任务 |
|---|---|---|
| AS-01 案例和义务 | [规范](specs/after-sales-management/spec.md) | 9.1—9.2、9.9—9.10、14.13—14.14 |
| AS-02 最近交付退回 | [规范](specs/after-sales-management/spec.md) | 9.3—9.4、15.2 |
| AS-03 修复与补发 | [规范](specs/after-sales-management/spec.md) | 9.5—9.6、15.2 |
| AS-04 池内直接报废 | [规范](specs/after-sales-management/spec.md) | 9.7—9.8、13.7—13.8、14.13—14.14、15.2 |
| AS-05 取消义务分支核对 | [规范](specs/after-sales-management/spec.md) | 9.9—9.10、15.2 |
| AS-06 金额与成本独立 | [规范](specs/after-sales-management/spec.md) | 9.1—9.2、9.9—9.10、11.3—11.4、12.1—12.2、14.13—14.14 |
| CA-01 预算和供料价值 | [规范](specs/business-calculation/spec.md) | 3.1—3.2 |
| CA-02 小组工作量一次取整 | [规范](specs/business-calculation/spec.md) | 3.3—3.4 |
| CA-03 基础工资与费用分配 | [规范](specs/business-calculation/spec.md) | 3.5—3.6、10.3—10.4 |
| CA-04 月度胶水纯计算 | [规范](specs/business-calculation/spec.md) | 3.7—3.8、10.5—10.6 |
| CA-05 胶水重量分配与舍入 | [规范](specs/business-calculation/spec.md) | 3.5—3.8、10.5—10.6 |
| CA-06 不自动裁决扣款 | [规范](specs/business-calculation/spec.md) | 10.3—10.8、14.15—14.16 |
| CS-01 现金资格与来源分配 | [规范](specs/cash-management/spec.md) | 8.7—8.8、9.9—9.10、10.11—10.12、12.1—12.2、14.11—14.16 |
| CS-02 日常收支与业务日期 | [规范](specs/cash-management/spec.md) | 12.3—12.4、14.17—14.18 |
| CS-03 可选凭证归本条 | [规范](specs/cash-management/spec.md) | 2.5—2.6、12.5—12.6、14.17—14.18 |
| CS-04 工资实际部分退回 | [规范](specs/cash-management/spec.md) | 12.7—12.8、15.3—15.4 |
| CS-05 冲销与真实退回互斥 | [规范](specs/cash-management/spec.md) | 12.9—12.10、15.4 |
| CS-06 按月汇总 | [规范](specs/cash-management/spec.md) | 13.5—13.6、14.19—14.20 |
| CO-01 材料历史标准归集 | [规范](specs/cost-ledger/spec.md) | 11.1—11.2 |
| CO-02 实际人工只取核定 | [规范](specs/cost-ledger/spec.md) | 10.7—10.10、11.3—11.4 |
| CO-03 售后与经营费用 | [规范](specs/cost-ledger/spec.md) | 9.9—9.10、11.3—11.4、12.3—12.4 |
| CO-04 分摊与不完整提示 | [规范](specs/cost-ledger/spec.md) | 11.5—11.6、14.23—14.24 |
| CO-05 冲正与失败恢复 | [规范](specs/cost-ledger/spec.md) | 11.5—11.6、14.23—14.24 |
| CU-01 客户资料与启停 | [规范](specs/customer-management/spec.md) | 4.1—4.2、14.3—14.4 |
| CU-02 收货覆盖与快照 | [规范](specs/customer-management/spec.md) | 4.3—4.4、8.1—8.2 |
| CU-03 版本与公开变化 | [规范](specs/customer-management/spec.md) | 4.1—4.4、13.1—13.2 |
| UI-01 全部原型业务入口 | [规范](specs/management-ui/spec.md) | 14.1—14.26、15.6 |
| UI-02 正式数据与命令所有者 | [规范](specs/management-ui/spec.md) | 13.7—13.8、14.11—14.18、15.6 |
| UI-03 关键页面边界 | [规范](specs/management-ui/spec.md) | 14.5—14.10 |
| UI-04 逐人工资与人工处理 | [规范](specs/management-ui/spec.md) | 10.9—10.12、12.1—12.10、14.15—14.16、15.3 |
| UI-05 状态反馈及权限 | [规范](specs/management-ui/spec.md) | 14.1—14.26、15.6 |
| UI-06 原型与正式验收区分 | [规范](specs/management-ui/spec.md) | 15.1—15.8 |
| UI-07 维护与失败恢复入口 | [规范](specs/management-ui/spec.md) | 2.11—2.12、11.5—11.6、13.7—13.8、14.5—14.26、15.6 |
| RP-01 公开来源和水位 | [规范](specs/operating-reports/spec.md) | 13.1—13.2 |
| RP-02 重建与差异对账 | [规范](specs/operating-reports/spec.md) | 13.3—13.4、14.25—14.26、15.5 |
| RP-03 指标不重复计数 | [规范](specs/operating-reports/spec.md) | 13.5—13.6 |
| RP-04 利润与工资显示 | [规范](specs/operating-reports/spec.md) | 13.5—13.6、14.19—14.20 |
| RP-05 主题权限与报表管理 | [规范](specs/operating-reports/spec.md) | 13.3—13.8、14.25—14.26 |
| OR-01 正式确认与历史约定 | [规范](specs/order-management/spec.md) | 8.1—8.2、14.11—14.12 |
| OR-02 约定变更与继任项 | [规范](specs/order-management/spec.md) | 8.3—8.4、14.11—14.12 |
| OR-03 减量和取消 | [规范](specs/order-management/spec.md) | 7.13—7.14、8.3—8.4 |
| OR-04 交付与消费原子关联 | [规范](specs/order-management/spec.md) | 8.5—8.6 |
| OR-05 付款资格和完成 | [规范](specs/order-management/spec.md) | 8.7—8.8、12.1—12.2、15.1 |
| OR-06 已有订单接管 | [规范](specs/order-management/spec.md) | 8.9—8.10、14.11—14.12、15.7 |
| PY-01 工资期间与独立参考 | [规范](specs/payroll-settlement/spec.md) | 10.3—10.4、14.15—14.16 |
| PY-02 成本逐项与人工填写 | [规范](specs/payroll-settlement/spec.md) | 10.3—10.8、14.15—14.16 |
| PY-03 成本状态和承担方 | [规范](specs/payroll-settlement/spec.md) | 10.3—10.4、14.15—14.16 |
| PY-04 逐人确认与调整 | [规范](specs/payroll-settlement/spec.md) | 10.7—10.10、15.3—15.4 |
| PY-05 胶水自然月设置 | [规范](specs/payroll-settlement/spec.md) | 10.5—10.6、14.19—14.20 |
| PY-06 已核定只读复核 | [规范](specs/payroll-settlement/spec.md) | 10.9—10.10、14.15—14.16 |
| PY-07 未核定不付款与停用激励 | [规范](specs/payroll-settlement/spec.md) | 10.3—10.4、10.11—10.12、12.1—12.2、14.15—14.16 |
| PL-01 身份与资源权限 | [规范](specs/platform-security/spec.md) | 2.1—2.2、2.9—2.12、14.1—14.2、14.19—14.20 |
| PL-02 审计与编号 | [规范](specs/platform-security/spec.md) | 2.3—2.4、2.11—2.12、14.21—14.22 |
| PL-03 文件正式化与引用保护 | [规范](specs/platform-security/spec.md) | 2.5—2.6、2.11—2.12、14.1—14.2 |
| PL-04 提交后任务 | [规范](specs/platform-security/spec.md) | 2.7—2.8、2.11—2.12、14.21—14.22 |
| PR-01 商品和标准方案 | [规范](specs/product-management/spec.md) | 5.1—5.2、14.5—14.6 |
| PR-02 自定义方案边界 | [规范](specs/product-management/spec.md) | 5.3—5.4、14.5—14.6 |
| PR-03 商品录入默认值 | [规范](specs/product-management/spec.md) | 5.1—5.2、14.5—14.6 |
| PR-04 最终供料依据 | [规范](specs/product-management/spec.md) | 5.5—5.8、8.1—8.2、11.1—11.2 |
| PR-05 历史参考成本完整分项 | [规范](specs/product-management/spec.md) | 5.7—5.8、10.3—10.4 |
| PO-01 需求与任务安排 | [规范](specs/production-operations/spec.md) | 7.1—7.4、14.9—14.10 |
| PO-02 日负载与新制产能 | [规范](specs/production-operations/spec.md) | 7.5—7.6、15.4 |
| PO-03 最终核验与三类结果 | [规范](specs/production-operations/spec.md) | 7.7—7.8、14.9—14.10 |
| PO-04 报废和物理去向正交 | [规范](specs/production-operations/spec.md) | 7.9—7.10、9.7—9.8 |
| PO-05 返工与补产区别 | [规范](specs/production-operations/spec.md) | 7.7—7.12、9.5—9.6 |
| PO-06 报废责任及来源 | [规范](specs/production-operations/spec.md) | 7.9—7.12、9.7—9.8、10.1—10.4 |
| PO-07 移交不可逆与减量 | [规范](specs/production-operations/spec.md) | 7.13—7.14、8.3—8.4、15.2 |
| PO-08 可追溯生产与来源 | [规范](specs/production-operations/spec.md) | 7.11—7.16、14.9—14.10 |
| SF-01 九模块公开边界 | [规范](specs/system-foundation/spec.md) | 1.4—1.5、1.9—1.10、13.7—13.8 |
| SF-02 历史事实与内容版本 | [规范](specs/system-foundation/spec.md) | 4.3—4.4、5.5—5.8、6.3—6.4、7.11—7.12 |
| SF-03 命令原子性及幂等 | [规范](specs/system-foundation/spec.md) | 2.3—2.4、2.11—2.12、15.4 |
| SF-04 金额数量与时间精度 | [规范](specs/system-foundation/spec.md) | 1.4—1.5、1.9—1.10、3.1—3.8、13.7—13.8 |
| SF-05 更正来源的完整消费 | [规范](specs/system-foundation/spec.md) | 1.4—1.6、7.11—7.12、10.1—10.2、11.5—11.6 |
| SF-06 一期上线证据 | [规范](specs/system-foundation/spec.md) | 15.1—15.8 |
| WF-01 员工就业历史 | [规范](specs/workforce-management/spec.md) | 6.1—6.2、14.7—14.8 |
| WF-02 小组历史成员和标准 | [规范](specs/workforce-management/spec.md) | 6.1—6.4、14.7—14.8 |
| WF-03 活动引用守卫 | [规范](specs/workforce-management/spec.md) | 6.5—6.6、7.3—7.6、15.4 |
| WF-04 标准版本隔离 | [规范](specs/workforce-management/spec.md) | 6.3—6.4 |

## 六原型覆盖

| 现有原型 | 正式页面任务 | 不能沿用的旧演示行为 |
|---|---|---|
| customers-element-plus.html | 14.3—14.4 | 浏览器 store 作为客户权威数据 |
| product-element-plus.html | 14.5—14.6 | 以演示默认值代替正式方案、供料和预算版本 |
| workforce-element-plus.html | 14.7—14.8 | 员工绑定账号或绕过活动引用守卫 |
| production-element-plus.html | 14.9—14.10 | 把转线下作为第四类完成数量、直接使用模板未渲染页面 |
| orders-element-plus.html | 14.11—14.12；售后 14.13—14.14 | 在前端拼接支付关联、把订单收付重新录入财务日常收支 |
| finance-element-plus.html | 14.15—14.20 | 自动扣款、资金账户、独立凭证池、把现金净额叫利润 |

## 特别确认项

- 返工无责任；报废和去向正交；池内报废不伪造任务；转线下不可取回。
- 商品保留标准售价且无目标工序输入；员工不绑定平台账号。
- 工资成本逐项参考、负责人填写；最终工资不自动减去处理明细；胶水按自然月参考。
- 收付凭证全部可选且随本条现金记录；订单收退款在订单入口；日常收支选择业务日期。
- 真实工资部分退回与错误现金整笔冲销分开；不重复记成本或伪造现金。
- 工程选择是本提案设计，不等于所有技术曾在业务基线中提前批准；创建提案不授权实施。

## 1.1 审查修复记录

| 发现 | 规范与设计 | 实施及验证 |
|---|---|---|
| 工资实际付款提前依赖未实现 cash | CS-01、UI-04、design D6 | 10.9—10.10 只做复核/调整；10.11—10.12 提供内部资格及关联；12.1—12.2 才执行真实现金及原子付款；14.15—14.16 验证页面只调用命令所有者 |
| OTHER_TASK 被强制关联生产来源 | PO-01、PO-03、design D5 | 7.3—7.4 验证无来源可安排及人员负载/槽位；7.7—7.8 验证有/无商品劳动来源，制品和材料不变；14.9—14.10 验证页面不伪造生产来源 |

本表记录的是规范和任务修复，不是业务测试已执行；所有实施任务仍保持未勾选。

## 1.2 全量复核闭环

| 问题类别 | 修复位置 | 验证边界 |
|---|---|---|
| 尚未实现认证/幂等却要求真实 HTTP 转绿 | tasks 1.4—1.10、2.11—2.12；design D8 | 契约与真实集成分阶段，不存在反向依赖 |
| 来源消费者先于提供者，缺明确 Query/HTTP | delivery-contracts §2、§3；tasks 各 owner 完成节点、13.7—13.8 | 来源前置可追溯，内部 API 不暴露网页 |
| 只有主体原型页、缺主数据配置/更正/接管等入口 | UI-07；delivery-contracts §2；tasks 14.5—14.20 | 各入口明确 owner 和真实页面测试 |
| 缺审计/任务恢复、成本归集失败/分摊、报表对账重建 | PL-04、CO-05、RP-02/RP-05、UI-07；tasks 14.21—14.26 | 三类恢复分别归各 owner，拒绝越权和重复事实 |
| 首次管理员引导与网页管理混淆 | PL-01；design D1；tasks 2.9—2.10 | 一次性 CLI，无匿名注册/默认密码 |
| 规范仅映射整个阶段，后续修改容易漏验 | 本表逐要求映射、verify-plan.py、review.md | 校验计划结构，不冒充业务测试通过 |

本轮未改变工资参考、人工决定、凭证可选、订单内收付及转线下等既定业务口径；详见 [审查记录](review.md)。

## 1.3 售后池处置归属修复

- AS-04、UI-02、design D5 和交付清单 A01 一致规定：orders 拥有 `disposeReturnedProduct` 网页命令，production 通过处置 Participant 在同一事务写生产事实。N02 不再承接售后池初次处置；已有责任独立更正仍保留。
- 任务 9.7—9.8 覆盖 orders 处置记录与 production 处置/责任/退出/移交原子性；13.7—13.8 校验正式 HTTP 归属及拒绝绕行；14.13—14.14 验证页面单一命令；15.2 核对跨模块事实引用。
- 计划校验增加该命令在交付矩阵中的唯一归属检查，不再仅校验 owner 是合法模块名；不以该静态守卫代替真实业务测试。

## 2. 实施与验证记录

- 记录日期：2026-10-21；记录人：chen。
- 范围：一期全部业务模块、管理端页面、来源 Feed 与 HTTP 适配；未部署、未归档、未提交。
- 结论：`tasks.md` 155 项全部完成；本表要求在实现侧均有公开 Query/命令、页面或测试证据。以下差异按实际实现记录，不以月汇总或报表投影替代业务事实。

### 2.1 全量门禁（新鲜证据）

| 门禁 | 命令 | 实际结果 |
|---|---|---|
| 后端全量 | `cd backend && ./mvnw clean verify` | 275 个真实 MySQL 集成测试、121 个单元/架构测试，0 失败 |
| 前端单元 | `pnpm --dir frontend test:unit` | 39 通过 |
| 前端类型与构建 | `pnpm --dir frontend typecheck`、`pnpm --dir frontend build` | 通过 |
| 页面闭环 | `pnpm --dir frontend exec playwright test` | 28 通过（含 `phase-one.spec.ts`、任务调整、可选凭证与窄屏筛选） |
| 计划校验 | `openspec validate implement-phase-one-business-system --strict` | valid |
| 计划结构 | `python3 openspec/changes/implement-phase-one-business-system/verify-plan.py` | 155 任务、78 要求、132 场景、23 交付行；结构通过 |
| 备份恢复/接管 | 见 `docs/operations/phase-one-runbook.md` | 9/9 订单、9/9 需求、10/10 现金、现金合计 540.00 一致；接管后订单与需求各 +1，现金不变 |

### 2.2 验收证据映射

| 验收域 | 证据 |
|---|---|
| 标准订单闭环 | `PhaseOneBusinessFlowIT`：订单→排班核验→交付→现金收款→完成；订单、生产、现金三方数量/金额一致 |
| 售后闭环 | `AfterSalesFinanceFlowIT`：10 件退回按 3 修复 + 7 报废转线下；补发只覆盖修复缺口；售后赔付独立现金；双端故障全回滚 |
| 工资人工决定 | `PayrollManualDecisionFlowIT`：同月两期间、空/零处理项不改变 1000 元核定、只读复核不改应付、跨月部分退回后人工调整不产生现金 |
| 并发与回滚 | `ConcurrencyAndRollbackIT`、`AfterSalesReturnDispositionIT`、`PayrollConfirmationIT`：并发付款/退回/冲销只有一方成功，无负数、超付或半笔现金；处置双端回滚 |
| 报表与重建 | `ReportingReconciliationIT`、`ReportingRebuildIT`：重复归集、失败恢复、影子重建、应收/成本/现金对账 |
| 生产任务调整 | `TaskAdjustmentIT`、`BusinessHttpCoverageIT`、`production.spec.ts`：改期/换主体/改工序/改明细、部分核验后锁定工序并保留原负载、依赖任务阻断、失败整笔回滚；HTTP 幂等重放与页面调整链路 |
| 管理恢复入口 | `business-ui` 页面与 E2E：审计只读检索、提交后任务重试/放弃、成本归集失败重试、成本更正/分摊、报表对账/重建 |
| 页面与权限 | `phase-one.spec.ts`、`navigation.spec.ts` 及各模块 spec：统一导航、登录过期、越权拒绝、空态、版本冲突、可选凭证、窄屏筛选 |

### 2.3 已知实现差异

- 生产任务独立“取消”与“调整”均已提供公开命令、HTTP 路由与页面入口。调整支持改期、换主体、改工序与增删改未核验明细；按员工/日期、产能和来源新旧键并集稳定锁序重算负载、产能、来源、槽位与活动预留，已核验事实保留原日期、主体和标准版本，存在核验事实时锁定工序。`TaskAdjustmentIT` 覆盖成功调整、部分核验后改期、工序锁定、依赖任务阻断和失败回滚；`production.spec.ts` 覆盖页面调整链路。
- 售后费用查询、生产转线下记录与售后案例列表/搜索均已提供公开 Query/HTTP：`GET /api/after-sales/costs`、`GET /api/production/offline-transfers`、`GET /api/after-sales/cases`；页面已接入费用与转线下记录展示。
- 现金凭证为可选附件：订单收付款、日常收支与售后赔付请求可携带正式文件引用，随现金命令同事务引用；上传组件在订单收款与日常收支页面复用。
- 部分核验按明细基准秒数分摊任务总负载，只移走被核验明细应占份额；调整与取消忽略已取消明细，避免多明细或调整后任务重复占用/错误保留。
- 普通任务验收报废责任现在同步发布 APPLY 修订，工资责任 Feed 可消费；`PayrollManualDecisionFlowIT` 已覆盖迟到责任复核且原核定/应付不变。
- 平台命令与现金命令已整体使用死锁重试模板；并发付款、退回与冲销专项测试通过。
- 一期不计算超额制作激励；不提供动态公式编辑；报表不把工资参考、处理金额或现金净额合并成利润。
