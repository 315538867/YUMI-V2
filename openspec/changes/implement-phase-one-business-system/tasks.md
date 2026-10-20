# 一期业务系统正式实现 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `openspec-delivery` to implement this plan through OpenSpec after explicit authorization. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 基于已确认一期方案建立真实可追溯的九模块业务系统及正式管理端，不复用原型缓存作为权威数据。

**Architecture:** 模块化单体，公开 API 与独立 Feed 协作；售后属于 orders，工资/成本/现金是 finance 内部子域。所有计划中的业务文件尚未创建，当前仅生成提案。

**Tech Stack:** Java 21 / Spring Boot 3.5 / Spring Modulith / JdbcClient / MySQL / Flyway；本提案选择 Maven Wrapper、Vue 3 / TypeScript / Vite / Element Plus / pnpm，实施首步固定兼容依赖版本。

基线：`a714ede433b37d9d987e7229d2fdead52d723ac1`；方案 `docs/一期/`；提案 1.3；2026-10-19；创建者 Codex；状态：所有实施任务未开始。

最后修订：2026-10-20，Codex；提案 1.3，业务基线提交不变。

## 执行约定

- 以下 `B/` 精确展开为 `backend/src/main/java/com/yumi/`，`T/` 为 `backend/src/test/java/com/yumi/`，`F/` 为 `frontend/`，均为计划新增路径，不是已存在文件。
- 后端每项实现涉及持久化时，须同时新增所属模块 Flyway 迁移 `backend/src/main/resources/db/migration/V<顺序号>__<模块与动作>.sql`，按执行顺序唯一编号；表字段以相应基线章节为准，不擅自合并权威表。
- 每组先新增列出的测试并确认目标行为为红，再实现使它转绿；不得把编译失败、容器不可用或错误命令冒充业务断言红。若工程缺失，先完成第 1 组。
- 每条测试任务覆盖本组关联 spec 的对应场景及基线验收；真实 MySQL 集成测试类以 `IT` 结尾，由 Failsafe 执行。完成条件同时包含相关已通过测试不回归。
- `delivery-contracts.md` 在某完成节点补充的 Query、HTTP、Feed 和页面行为，必须先纳入该节点的前序红测试；不能只在实现任务末尾声称覆盖。来源身份、完整分页、权限和失败恢复均属于完成条件。
- 各模块按 `delivery-contracts.md` 交付面向用户的命令与 Query 的 HTTP Controller/Route、请求响应 contract、认证/幂等/错误码映射；内部 Participant、Feed、快照及编排专用接口不开放 HTTP。第 1 组仅建纯契约，第 2 组绑定真实平台实现，第 13 组复验全部业务适配器；不得以内部服务代替页面可用接口。
- 所有前端业务页同时配套对应模块的 `composables/use<业务>.ts`、`services/<业务>Api.ts`、`contracts/<业务>.ts`；页面不直接持有 HTTP 或业务计算。
- 阶段验证命令在 `design.md` D8 定义；只有实际执行并记录结果后才勾选。任务中没有任何自动提交、部署、推送或后台常驻服务授权。

## 1. 工程与边界基线

- [ ] 1.1 校验提案授权和基线漂移；目标：`docs/一期/`、本 change 的 `coverage.md`；依赖：用户明确实施授权；完成：确认业务基线仍与源提交一致或先更新提案版本；验证：`git diff a714ede -- docs/一期/`，提案关联状态更新单列，不误当业务漂移。
- [ ] 1.2 创建 `backend/pom.xml`、Wrapper、`YumiApplication.java` 及测试/迁移配置，固定兼容依赖版本；依赖：1.1；完成：单元和独立容器 MySQL 集成测试入口均可运行；验证：最小启动测试及 `cd backend && ./mvnw verify`，不连接未知数据库。
- [ ] 1.3 创建 `frontend/package.json`、锁文件、`src/app/App.vue`、Vite/TypeScript/Vitest/Playwright 配置及 smoke 测试；依赖：1.1；完成：建立 typecheck、unit、build 与自动化页面脚本；验证：对应四个入口在空应用上运行，精确版本写锁文件。
- [ ] 1.4 在 `T/architecture/ModuleBoundaryTest.java`、`T/platform/ValueContractTest.java`、`T/platform/CommandBatchContractTest.java` 添加模块依赖、数值精度及完整批次值对象契约测试；依赖：1.2；完成：目标守卫未实现时行为断言为红；本阶段只验证纯契约，不测试尚未实现的持久幂等、认证或业务 Feed；验证：定向执行这三个单元测试类。
- [ ] 1.5 建立九模块 `B/<模块>/package-info.java`、公开 API 包、金额/重量/秒值对象和模块边界约束；依赖：1.4；完成：禁止跨模块内部类型和跨模块外键，精度非法输入明确报错；验证：`ModuleBoundaryTest`、`ValueContractTest` 转绿。
- [ ] 1.6 在 `B/platform/aftercommit/api/SourceCommandBatch.java` 定义不可拆分的命令批次、完整性标识和来源游标承载契约；依赖：1.4、1.5；完成：拒绝不完整批次被声明为完整；数据库发布、分页和重放由 7.11—7.12、10.1—10.2 验证，不在此声称实现；验证：`CommandBatchContractTest` 转绿。
- [ ] 1.7 根据批准的本提案技术选择回写 `docs/一期/03-系统总体架构与模块依赖.md` 7.1 和工程 README，区分业务基线与工程决策；依赖：1.2、1.3；完成：实际工程选型、目录及验证命令与文档一致；验证：配置/锁文件对照，不伪称已有实施结果。
- [ ] 1.8 验证 `backend/`、`frontend/` 骨架和隔离测试环境；依赖：1.5、1.6、1.7；完成：基础构建、类型、架构及容器迁移 smoke 全部通过；验证：全量骨架命令，保存输出，失败不得进入业务实现。
- [ ] 1.9 在 `T/architecture/HttpContractBoundaryTest.java`、`T/platform/HttpWireContractTest.java` 添加 HTTP 适配依赖、十进制字符串及错误序列化单元测试；依赖：1.8；完成：适配器只调用本模块公开命令或 Query，不直接调用 Repository、Participant、Feed；认证/幂等使用明确测试替身，只测映射契约；验证：定向单元测试确认行为为红，真实安全和持久幂等留到 2.11。
- [ ] 1.10 在 `B/platform/http/` 和 `F/src/platform/services/apiClient.ts` 建立 HTTP 上下文、错误映射、幂等头及十进制字符串契约；依赖：1.9；完成：业务模块适配器后续在各自模块实现，platform 不反向依赖业务模块；不以替身结果宣称真实认证或幂等通过；验证：`HttpContractBoundaryTest`、`HttpWireContractTest` 转绿。

## 2. 平台身份、幂等及文件

能力规范：`specs/platform-security/spec.md`。

- [ ] 2.1 在 `T/platform/IdentityAuthorizationTest.java` 添加 实现登录退出、会话状态与资源权限，含工资敏感访问；不增加员工账号绑定 的测试；依赖：1.10；完成：覆盖“无权限与失效会话直接访问命令均被拒绝，合法会话可操作”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dtest=IdentityAuthorizationTest test`。
- [ ] 2.2 在 `B/platform/identity/application/IdentityApplicationService.java`、`B/platform/authorization/application/PermissionService.java` 实现登录退出、会话状态与资源权限，含工资敏感访问；不增加员工账号绑定；依赖：2.1；完成：无权限与失效会话直接访问命令均被拒绝，合法会话可操作；验证：`cd backend && ./mvnw -Dtest=IdentityAuthorizationTest test` 转绿并通过已有模块边界测试。
- [ ] 2.3 在 `T/integration/CommandIdempotencyIT.java` 添加 实现持久幂等、并发编号及审计；相同键不同输入拒绝、成功重试复用原结果 的测试；依赖：2.2；完成：覆盖“并发请求只产生一份成功业务结果，编号唯一且审计保留”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=CommandIdempotencyIT verify`。
- [ ] 2.4 在 `B/platform/idempotency/application/IdempotentCommandService.java`、`B/platform/audit/application/AuditService.java`、`B/platform/numbering/application/BusinessNumberService.java` 实现持久幂等、并发编号及审计；相同键不同输入拒绝、成功重试复用原结果；依赖：2.3；完成：并发请求只产生一份成功业务结果，编号唯一且审计保留；验证：`cd backend && ./mvnw -Dit.test=CommandIdempotencyIT verify` 转绿并通过已有模块边界测试。
- [ ] 2.5 在 `T/integration/FileLifecycleIT.java` 添加 实现上传校验、正式化、引用及删除保护，业务事务失败撤销正式引用 的测试；依赖：2.4；完成：覆盖“临时或无权文件不能引用，已引用文件不可直接删除，失败无孤立引用”及失败路径，并确认目标断言为红；覆盖文件大小/类型配置校验、无权预览下载及引用方资源权限；验证：`cd backend && ./mvnw -Dit.test=FileLifecycleIT verify`。
- [ ] 2.6 在 `B/platform/file/application/FileApplicationService.java` 实现上传校验、正式化、引用及删除保护，业务事务失败撤销正式引用；依赖：2.5；完成：临时或无权文件不能引用，已引用文件不可直接删除，失败无孤立引用；提供文件元数据和授权读取能力，下载/预览须校验引用方资源权限，不能仅凭文件 ID 读取；验证：`cd backend && ./mvnw -Dit.test=FileLifecycleIT verify` 转绿并通过已有模块边界测试。
- [ ] 2.7 在 `T/integration/AfterCommitTaskIT.java` 添加 实现提交后任务、租约竞争、退避和人工恢复状态 的测试；依赖：2.6；完成：覆盖“回滚事务无可执行任务，重试不影响原业务，租约只被一名执行者持有”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=AfterCommitTaskIT verify`。
- [ ] 2.8 在 `B/platform/aftercommit/application/AfterCommitTaskService.java` 实现提交后任务、租约竞争、退避和人工恢复状态；依赖：2.7；完成：回滚事务无可执行任务，重试不影响原业务，租约只被一名执行者持有；验证：`cd backend && ./mvnw -Dit.test=AfterCommitTaskIT verify` 转绿并通过已有模块边界测试。
- [ ] 2.9 在 `T/integration/PlatformAdministrationIT.java` 添加 实现账号创建/停用、角色维护、账号授权、首次管理员安全引导及审计的测试；依赖：2.8；完成：平台管理员可维护账号和角色，停用账号立即失效，员工资料不出现绑定字段，未授权操作者不能越权；同时覆盖空系统一次性管理员 CLI、非空系统拒绝重置及并发引导只成功一次，不依赖未来业务模块；验证：`cd backend && ./mvnw -Dit.test=PlatformAdministrationIT verify`，并确认目标断言为红。
- [ ] 2.10 在 `B/platform/identity/application/AccountAdministrationService.java`、`B/platform/authorization/application/RoleAdministrationService.java`、对应 `api/http/` 适配层实现账号创建/停用、角色维护、账号授权、首次管理员安全引导及审计；依赖：2.9；完成：账号和角色变更经过权限、版本、幂等和审计校验，员工仍不绑定平台账号；在 `B/platform/identity/bootstrap/InitialAdministratorCommand.java` 提供显式启用的一次性运维入口，安全读取初始秘密、不记录口令、无默认口令、空系统守卫原子创建账号角色；测试环境也先用此入口初始化，再用正式 API 准备业务数据；验证：`cd backend && ./mvnw -Dit.test=PlatformAdministrationIT verify` 转绿并通过 `IdentityAuthorizationTest`。
- [ ] 2.11 在 `T/integration/PlatformHttpContractIT.java` 添加真实平台 HTTP 集成测试；依赖：2.10；完成：通过真实登录/账号命令验证失效会话、权限撤销、幂等重放及同键异输入冲突；通过审计、文件和任务接口验证授权读取、失败重试/放弃；不得替换认证或幂等实现，目标断言为红；验证：`cd backend && ./mvnw -Dit.test=PlatformHttpContractIT verify`。
- [ ] 2.12 按 `delivery-contracts.md` P01—P03 实现平台 HTTP 适配器、审计与任务 Query 及授权文件读取；依赖：2.11；完成：绑定真实权限、幂等、事务、审计和错误映射，重试不重复业务事实，非管理员不能操作后台任务；不增加匿名管理员注册路由；验证：`PlatformHttpContractIT` 转绿并运行 `cd backend && ./mvnw verify`。

## 3. 计算中心与精度

能力规范：`specs/business-calculation/spec.md`。

- [ ] 3.1 在 `T/calculation/ProductBudgetCalculatorTest.java` 添加 实现商品单件预算、供料承担、标准产能与零售价利润率，保留规则及舍入版本 的测试；依赖：2.12；完成：覆盖“105 克损耗示例、客户供料分列、负利润及零售价均与规范一致”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dtest=ProductBudgetCalculatorTest test`。
- [ ] 3.2 在 `B/calculation/api/ProductBudgetCalculator.java`、`B/calculation/api/CapacityCalculator.java` 实现商品单件预算、供料承担、标准产能与零售价利润率，保留规则及舍入版本；依赖：3.1；完成：105 克损耗示例、客户供料分列、负利润及零售价均与规范一致；验证：`cd backend && ./mvnw -Dtest=ProductBudgetCalculatorTest test` 转绿并通过已有模块边界测试。
- [ ] 3.3 在 `T/calculation/WorkloadCalculatorTest.java` 添加 实现个人、小组和已核验/预留负载纯计算，按任务类型决定是否除效率并只在适用整体结果取整 的测试；依赖：3.2；完成：覆盖“60 加 60 秒、效率 1.3 得 93 秒，其他小组任务录入 60 分钟仍得 3600 秒，不重复取整且不掩盖超负载”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dtest=WorkloadCalculatorTest test`。
- [ ] 3.4 在 `B/calculation/api/WorkloadCalculator.java` 实现个人、小组和已核验/预留负载纯计算，按任务类型决定是否除效率并只在适用整体结果取整；依赖：3.3；完成：生产/修复小组任务按效率整体折算，其他小组任务不再除效率；验证：`cd backend && ./mvnw -Dtest=WorkloadCalculatorTest test` 转绿并通过已有模块边界测试。
- [ ] 3.5 在 `T/calculation/CostAndMoneyAllocationTest.java` 添加 实现不同精度金额乘法、分配及稳定余数，不将成本精度隐式转为结算精度 的测试；依赖：3.4；完成：覆盖“两位工资与四位非胶水成本各自分配守恒，输入排列不改结果”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dtest=CostAndMoneyAllocationTest test`。
- [ ] 3.6 在 `B/calculation/api/CostAmountCalculator.java`、`B/calculation/api/MoneyAllocationCalculator.java`、`B/calculation/api/CostAllocationCalculator.java` 实现不同精度金额乘法、分配及稳定余数，不将成本精度隐式转为结算精度；依赖：3.5；完成：两位工资与四位非胶水成本各自分配守恒，输入排列不改结果；验证：`cd backend && ./mvnw -Dtest=CostAndMoneyAllocationTest test` 转绿并通过已有模块边界测试。
- [ ] 3.7 在 `T/calculation/MonthlyGlueAllowanceCalculatorTest.java` 添加 实现自然月胶水重量分配、稳定顺序减免和逐行舍入；不接触数据库或自动扣款 的测试；依赖：3.6；完成：覆盖“60/80 克混合价格、免除重算、三人 1 克和 0.0009 元合计样例通过”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dtest=MonthlyGlueAllowanceCalculatorTest test`。
- [ ] 3.8 在 `B/calculation/api/MonthlyGlueAllowanceCalculator.java` 实现自然月胶水重量分配、稳定顺序减免和逐行舍入；不接触数据库或自动扣款；依赖：3.7；完成：60/80 克混合价格、免除重算、三人 1 克和 0.0009 元合计样例通过；验证：`cd backend && ./mvnw -Dtest=MonthlyGlueAllowanceCalculatorTest test` 转绿并通过已有模块边界测试。

## 4. 客户主数据

能力规范：`specs/customer-management/spec.md`。

- [ ] 4.1 在 `T/integration/CustomerLifecycleIT.java` 添加 实现客户资料、启停和行版本校验，不扩展 CRM 的测试；依赖：2.12；完成：覆盖“停用不破坏历史，冲突编辑拒绝，停用客户不能用于新订单”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=CustomerLifecycleIT verify`。
- [ ] 4.2 在 `B/customer/application/CustomerApplicationService.java` 实现客户资料、启停和行版本校验，不扩展 CRM；依赖：4.1；完成：停用不破坏历史，冲突编辑拒绝，停用客户不能用于新订单；验证：`cd backend && ./mvnw -Dit.test=CustomerLifecycleIT verify` 转绿并通过已有模块边界测试。
- [ ] 4.3 在 `T/integration/CustomerSnapshotIT.java` 添加 实现正式引用快照、收货覆盖指纹和变化数据源 的测试；依赖：4.2；完成：覆盖“同客户不同收货覆盖分开冻结且不改默认地址，原版本可重复解析”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=CustomerSnapshotIT verify`。
- [ ] 4.4 在 `B/customer/api/CustomerSnapshotService.java`、`B/customer/api/CustomerReportingFeed.java` 实现正式引用快照、收货覆盖指纹和变化数据源；依赖：4.3；完成：同客户不同收货覆盖分开冻结且不改默认地址，原版本可重复解析；验证：`cd backend && ./mvnw -Dit.test=CustomerSnapshotIT verify` 转绿并通过已有模块边界测试。

## 5. 商品、方案与生产依据

能力规范：`specs/product-management/spec.md`。

- [ ] 5.1 在 `T/integration/ProductAggregateIT.java` 添加 实现商品完整提交、录入默认值、三道工序及额外单件费用，保留售价且拒绝商品目标工序 的测试；依赖：3.8；完成：覆盖“预算失败整体回滚；修改默认值不回写商品和历史业务”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=ProductAggregateIT verify`。
- [ ] 5.2 在 `B/product/application/ProductApplicationService.java`、`B/product/application/ProductEntryDefaultsService.java` 实现商品完整提交、录入默认值、三道工序及额外单件费用，保留售价且拒绝商品目标工序；依赖：5.1；完成：预算失败整体回滚；修改默认值不回写商品和历史业务；同时实现制作星级、工序标准时间选项及录入默认值维护，带出值保存为商品自身标准；验证：`cd backend && ./mvnw -Dit.test=ProductAggregateIT verify` 转绿并通过已有模块边界测试。
- [ ] 5.3 在 `T/integration/ProductSchemeIT.java` 添加 实现固定三工序标准方案与允许短流程的自定义方案，仅开放明确非人工覆盖 的测试；依赖：5.2；完成：覆盖“自定义人工、工时、提成覆盖被拒绝，商品修改重算当前相关方案”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=ProductSchemeIT verify`。
- [ ] 5.4 在 `B/product/application/ProductSchemeApplicationService.java` 实现固定三工序标准方案与允许短流程的自定义方案，仅开放明确非人工覆盖；依赖：5.3；完成：自定义人工、工时、提成覆盖被拒绝，商品修改重算当前相关方案；验证：`cd backend && ./mvnw -Dit.test=ProductSchemeIT verify` 转绿并通过已有模块边界测试。
- [ ] 5.5 在 `T/integration/ProductSnapshotIT.java` 添加 实现产品/方案内容版本快照和其他任务历史人工引用 的测试；依赖：5.4；完成：覆盖“相同版本复用，定义变更不改旧快照，无条件重复生成被防止”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=ProductSnapshotIT verify`。
- [ ] 5.6 在 `B/product/api/ProductSnapshotService.java` 实现产品/方案内容版本快照和其他任务历史人工引用；依赖：5.5；完成：相同版本复用，定义变更不改旧快照，无条件重复生成被防止；验证：`cd backend && ./mvnw -Dit.test=ProductSnapshotIT verify` 转绿并通过已有模块边界测试。
- [ ] 5.7 在 `T/integration/ProductionBasisIT.java` 添加 实现最终供料依据、当前工序材料专用解析和完整参考分项解析，返回稳定指纹 的测试；依赖：5.6；完成：覆盖“订单供料覆盖贯穿成本输入，完整参考含人工和分摊，缺依据不回退当前值”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=ProductionBasisIT verify`。
- [ ] 5.8 在 `B/product/api/ProductCostBasisQuery.java` 实现最终供料依据、当前工序材料专用解析和完整参考分项解析，返回稳定指纹；依赖：5.7；完成：订单供料覆盖贯穿成本输入，完整参考含人工和分摊，缺依据不回退当前值；按交付清单提供 `B/product/api/ProductReportingFeed.java`、列表详情及配置 Query；用 ProductSnapshotIT 验证稳定分页和历史不被当前变更覆盖；验证：`cd backend && ./mvnw -Dit.test=ProductionBasisIT verify` 转绿并通过已有模块边界测试。

## 6. 人员、小组与活动守卫

能力规范：`specs/workforce-management/spec.md`。

- [ ] 6.1 在 `T/integration/WorkforceLifecycleIT.java` 添加 实现就业历史、资格、小组成员、效率及有效时间，不建立账号绑定 的测试；依赖：2.12；完成：覆盖“返聘保留身份和就业链，成员资格约束生效，历史读取不随当前变更”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=WorkforceLifecycleIT verify`。
- [ ] 6.2 在 `B/workforce/application/EmployeeApplicationService.java`、`B/workforce/application/TeamApplicationService.java` 实现就业历史、资格、小组成员、效率及有效时间，不建立账号绑定；依赖：6.1；完成：返聘保留身份和就业链，成员资格约束生效，历史读取不随当前变更；验证：`cd backend && ./mvnw -Dit.test=WorkforceLifecycleIT verify` 转绿并通过已有模块边界测试。
- [ ] 6.3 在 `T/integration/WorkforceSnapshotIT.java` 添加 实现主体和时间标准快照，唯一键包含稳定标准标识与内容版本 的测试；依赖：6.2；完成：覆盖“两个标准同为版本 1 不碰撞，历史成员可在离职后解析”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=WorkforceSnapshotIT verify`。
- [ ] 6.4 在 `B/workforce/api/AssignmentSubjectSnapshotService.java` 实现主体和时间标准快照，唯一键包含稳定标准标识与内容版本；依赖：6.3；完成：两个标准同为版本 1 不碰撞，历史成员可在离职后解析；验证：`cd backend && ./mvnw -Dit.test=WorkforceSnapshotIT verify` 转绿并通过已有模块边界测试。
- [ ] 6.5 在 `T/integration/WorkforceReservationIT.java` 添加 实现 prepare/commit 预留、调整释放和可用性变更守卫，遵循统一锁序 的测试；依赖：6.4；完成：覆盖“资格撤销与任务预留竞争只产生合法状态，终态释放不删除历史引用”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=WorkforceReservationIT verify`。
- [ ] 6.6 在 `B/workforce/api/WorkforceActivityReservationParticipant.java` 实现 prepare/commit 预留、调整释放和可用性变更守卫，遵循统一锁序；依赖：6.5；完成：资格撤销与任务预留竞争只产生合法状态，终态释放不删除历史引用；按交付清单提供 `B/workforce/api/WorkforceReportingFeed.java`、员工/小组历史、标准和活动引用查询；在 WorkforceReservationIT 中覆盖变化 Feed 的分页及版本稳定性；验证：`cd backend && ./mvnw -Dit.test=WorkforceReservationIT verify` 转绿并通过已有模块边界测试。

## 7. 生产任务和事实闭环

能力规范：`specs/production-operations/spec.md`。

- [ ] 7.1 在 `T/integration/ProductionDemandIT.java` 添加 实现新制/修复需求、稳定生产来源和冻结生产依据，不产生任务 的测试；依赖：3.8、5.8、6.6；完成：覆盖“需求创建幂等，来源类型与工序合法，依据与上游指纹一致”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=ProductionDemandIT verify`。
- [ ] 7.2 在 `B/production/api/ProductionDemandParticipant.java` 实现新制/修复需求、稳定生产来源和冻结生产依据，不产生任务；依赖：7.1；完成：需求创建幂等，来源类型与工序合法，依据与上游指纹一致；验证：`cd backend && ./mvnw -Dit.test=ProductionDemandIT verify` 转绿并通过已有模块边界测试。
- [ ] 7.3 在 `T/integration/TaskArrangementIT.java` 添加 实现按任务类型区分安排：生产/返工绑定来源并预留，OTHER_TASK 无生产需求和来源，仅按整体分钟预留人员负载、活动与日期槽位 的测试；依赖：7.2；完成：覆盖“同一剩余来源被并发安排不能超量，正常/超额/返工类型校验符合基线 11；无来源 OTHER_TASK 可创建，个人或小组全体成员负载正确，冲突日期拒绝且不占新制产能”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=TaskArrangementIT verify`。
- [ ] 7.4 在 `B/production/application/TaskArrangementService.java` 实现按任务类型区分安排：生产/返工绑定来源并预留，OTHER_TASK 无生产需求和来源，仅按整体分钟预留人员负载、活动与日期槽位；依赖：7.3；完成：同一剩余来源被并发安排不能超量，正常/超额/返工类型校验符合基线 11；无来源 OTHER_TASK 可创建，个人或小组全体成员负载正确，冲突日期拒绝且不占新制产能；验证：`cd backend && ./mvnw -Dit.test=TaskArrangementIT verify` 转绿并通过已有模块边界测试。
- [ ] 7.5 在 `T/integration/DailyLoadAndCapacityIT.java` 添加 实现已核验负载与未核验预留，以及仅新制制作的商品日上限 的测试；依赖：7.4；完成：覆盖“已做 6 小时后再排 4 小时不能显示未超 8 小时；修复不占新制产能”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=DailyLoadAndCapacityIT verify`。
- [ ] 7.6 在 `B/production/application/DailyLoadService.java`、`B/production/application/NewProductCapacityService.java` 实现已核验负载与未核验预留，以及仅新制制作的商品日上限；依赖：7.5；完成：已做 6 小时后再排 4 小时不能显示未超 8 小时；修复不占新制产能；验证：`cd backend && ./mvnw -Dit.test=DailyLoadAndCapacityIT verify` 转绿并通过已有模块边界测试。
- [ ] 7.7 在 `T/integration/TaskVerificationIT.java` 添加 实现一次最终核验、未完成释放、三类线上结果和其他任务核验 的测试；依赖：7.6；完成：覆盖“100/98/2 样例与全部数量方程成立，OTHER_TASK 不生成制品数量、阶段或材料成本来源；关联商品时按冻结的其他任务单件人工费及完成数量发布劳动来源，未关联商品时不自动发布计件工资来源”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=TaskVerificationIT verify`。
- [ ] 7.8 在 `B/production/application/TaskVerificationService.java` 实现一次最终核验、未完成释放、三类线上结果和其他任务核验；依赖：7.7；完成：100/98/2 样例与全部数量方程成立，OTHER_TASK 不生成制品数量、阶段或材料成本来源；关联商品时按冻结的其他任务单件人工费及完成数量发布劳动来源，未关联商品时不自动发布计件工资来源；验证：`cd backend && ./mvnw -Dit.test=TaskVerificationIT verify` 转绿并通过已有模块边界测试。
- [ ] 7.9 在 `T/integration/ProductionDispositionIT.java` 添加 实现无责任返工、报废责任拆分、销毁和唯一终态退出，缺陷与责任各自追溯 的测试；依赖：7.8；完成：覆盖“5 件缺陷中的 3 返工不归责，报废转线下只退出一次，责任免除不改物理量”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=ProductionDispositionIT verify`。
- [ ] 7.10 在 `B/production/application/DispositionApplicationService.java`、`B/production/application/ResponsibilityApplicationService.java` 实现无责任返工、报废责任拆分、销毁和唯一终态退出，缺陷与责任各自追溯；依赖：7.9；完成：5 件缺陷中的 3 返工不归责，报废转线下只退出一次，责任免除不改物理量；落实 `B/production/application/DefectReasonAdministrationService.java` 与缺陷原因查询，按基线校验启停及历史引用，纳入本组质量处置测试；验证：`cd backend && ./mvnw -Dit.test=ProductionDispositionIT verify` 转绿并通过已有模块边界测试。
- [ ] 7.11 在 `T/integration/ProductionCorrectionIT.java` 添加 实现反向/替代事实、更正前后投影及劳动、材料、责任完整命令批次 Feed 的测试；依赖：7.10；完成：覆盖“报废改非报废只反向旧责任；所有更正批次完整可重放且历史负载不搬移”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=ProductionCorrectionIT verify`。
- [ ] 7.12 在 `B/production/application/ProductionFactCorrectionService.java` 实现反向/替代事实、更正前后投影及劳动、材料、责任完整命令批次 Feed；依赖：7.11；完成：报废改非报废只反向旧责任；所有更正批次完整可重放且历史负载不搬移；验证：`cd backend && ./mvnw -Dit.test=ProductionCorrectionIT verify` 转绿并通过已有模块边界测试。
- [ ] 7.13 在 `T/integration/ProductionTransferAndConsumptionIT.java` 添加 实现合法来源消费、反向消费、订单减量移交与禁止移交更正 的测试；依赖：7.12；完成：覆盖“已移交不能取回，已制减量无移交拒绝，发货失败不留下消费”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=ProductionTransferAndConsumptionIT verify`。
- [ ] 7.14 在 `B/production/api/ProductionConsumptionParticipant.java`、`B/production/api/ProductionOfflineInventoryTransferParticipant.java` 实现合法来源消费、反向消费、订单减量移交与禁止移交更正；依赖：7.13；完成：已移交不能取回，已制减量无移交拒绝，发货失败不留下消费；验证：`cd backend && ./mvnw -Dit.test=ProductionTransferAndConsumptionIT verify` 转绿并通过已有模块边界测试。
- [ ] 7.15 在 `T/integration/ProductionTraceIT.java` 添加 实现订单到实际事实的追溯与公开数量投影，输出来源、缺口和延迟可识别信息 的测试；依赖：7.14；完成：覆盖“读模型能核对两套数量账，不从计划数量虚构完成事实”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=ProductionTraceIT verify`。
- [ ] 7.16 在 `B/production/api/ProductionQuery.java`、`B/production/api/ProductionReportingFeed.java` 实现订单到实际事实的追溯与公开数量投影，输出来源、缺口和延迟可识别信息；依赖：7.15；完成：读模型能核对两套数量账，不从计划数量虚构完成事实；验证：`cd backend && ./mvnw -Dit.test=ProductionTraceIT verify` 转绿并通过已有模块边界测试。

## 8. 订单确认、变更及履约

能力规范：`specs/order-management/spec.md`。

- [ ] 8.1 在 `T/integration/OrderConfirmationIT.java` 添加 实现订单一次确认、客户及商品快照、最终交付供料依据和生产需求建立 的测试；依赖：4.4、5.8、7.16；完成：覆盖“任何参与端失败无半条订单，幂等重试复用成功结果，无服务端草稿”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=OrderConfirmationIT verify`。
- [ ] 8.2 在 `B/orders/trade/application/OrderApplicationService.java` 实现订单一次确认、客户及商品快照、最终交付供料依据和生产需求建立；依赖：8.1；完成：任何参与端失败无半条订单，幂等重试复用成功结果，无服务端草稿；验证：`cd backend && ./mvnw -Dit.test=OrderConfirmationIT verify` 转绿并通过已有模块边界测试。
- [ ] 8.3 在 `T/integration/OrderChangeIT.java` 添加 实现约定版本/继任项、未制取消与已制移交，并同事务释放已有任务预留 的测试；依赖：8.2；完成：覆盖“改变供料不改旧事实，混合订单任务只释放目标来源，新旧锁集合固定”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=OrderChangeIT verify`。
- [ ] 8.4 在 `B/orders/trade/application/OrderChangeService.java` 实现约定版本/继任项、未制取消与已制移交，并同事务释放已有任务预留；依赖：8.3；完成：改变供料不改旧事实，混合订单任务只释放目标来源，新旧锁集合固定；验证：`cd backend && ./mvnw -Dit.test=OrderChangeIT verify` 转绿并通过已有模块边界测试。
- [ ] 8.5 在 `T/integration/OrderDeliveryIT.java` 添加 实现发货、消费引用、更正及交付履约投影 的测试；依赖：8.4；完成：覆盖“并发发货不超可用，反向失败不改发货，历史收货信息不回读客户现值”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=OrderDeliveryIT verify`。
- [ ] 8.6 在 `B/orders/fulfillment/application/OrderDeliveryService.java` 实现发货、消费引用、更正及交付履约投影；依赖：8.5；完成：并发发货不超可用，反向失败不改发货，历史收货信息不回读客户现值；验证：`cd backend && ./mvnw -Dit.test=OrderDeliveryIT verify` 转绿并通过已有模块边界测试。
- [ ] 8.7 在 `T/integration/OrderReceivableIT.java` 添加 实现应收/退款义务、支付资格和关联，交付与付款同时满足才完成 的测试；依赖：8.6；完成：覆盖“超义务退款拒绝，付款足但未交付不能完成，orders 不依赖 finance”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=OrderReceivableIT verify`。
- [ ] 8.8 在 `B/orders/receivable/api/OrderPaymentParticipant.java`、`B/orders/fulfillment/application/OrderCompletionService.java` 实现应收/退款义务、支付资格和关联，交付与付款同时满足才完成；依赖：8.7；完成：超义务退款拒绝，付款足但未交付不能完成，orders 不依赖 finance；验证：`cd backend && ./mvnw -Dit.test=OrderReceivableIT verify` 转绿并通过已有模块边界测试。
- [ ] 8.9 在 `T/integration/OpeningOrderIT.java` 添加 实现已有订单及历史生产数量的正式接管命令，不读取原型缓存 的测试；依赖：8.8；完成：覆盖“接管只写明确基线，不额外生成历史劳动、成本或责任扣款来源”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=OpeningOrderIT verify`。
- [ ] 8.10 在 `B/orders/trade/application/OpeningOrderService.java`、`B/production/api/ProductionOpeningApplicationService.java` 实现已有订单及历史生产数量的正式接管命令，不读取原型缓存；依赖：8.9；完成：接管只写明确基线，不额外生成历史劳动、成本或责任扣款来源；按交付清单提供 `B/orders/trade/api/OrderReportingFeed.java`、订单列表/详情及履约 Query，接管经 orders 编排；生产接管 API 仅后端使用、不独立开放网页命令；验证：`cd backend && ./mvnw -Dit.test=OpeningOrderIT verify` 转绿并通过已有模块边界测试。

## 9. 售后义务与原制品回流

能力规范：`specs/after-sales-management/spec.md`。

- [ ] 9.1 在 `T/integration/AfterSalesCaseIT.java` 添加 实现单个已完成订单的售后受理、配合/数量/金额义务及结案 的测试；依赖：8.10；完成：覆盖“无订单和跨订单受理拒绝，任何有效义务未满足均不能结案”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=AfterSalesCaseIT verify`。
- [ ] 9.2 在 `B/orders/aftersales/application/AfterSalesApplicationService.java` 实现单个已完成订单的售后受理、配合/数量/金额义务及结案；依赖：9.1；完成：无订单和跨订单受理拒绝，任何有效义务未满足均不能结案；验证：`cd backend && ./mvnw -Dit.test=AfterSalesCaseIT verify` 转绿并通过已有模块边界测试。
- [ ] 9.3 在 `T/integration/AfterSalesReturnIT.java` 添加 实现最近交付退回、池重入、原样退还及对应消费 的测试；依赖：9.2；完成：覆盖“累计退回不超最近交付，再次退回引用最新交付，不增加制作量”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=AfterSalesReturnIT verify`。
- [ ] 9.4 在 `B/orders/aftersales/application/AfterSalesReturnService.java`、`B/production/api/ProductionAfterSalesReturnParticipant.java` 实现最近交付退回、池重入、原样退还及对应消费；依赖：9.3；完成：累计退回不超最近交付，再次退回引用最新交付，不增加制作量；验证：`cd backend && ./mvnw -Dit.test=AfterSalesReturnIT verify` 转绿并通过已有模块边界测试。
- [ ] 9.5 在 `T/integration/AfterSalesRepairRouteIT.java` 添加 实现修复/补发需求、仅当前缺口的分次转路线及继任义务 的测试；依赖：9.4；完成：覆盖“3 件缺口已转 1 只能再转 2，补发同事务建立新制需求但不排任务”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=AfterSalesRepairRouteIT verify`。
- [ ] 9.6 在 `B/orders/aftersales/application/AfterSalesRouteService.java`、`B/production/api/ProductionAfterSalesDemandParticipant.java` 实现修复/补发需求、仅当前缺口的分次转路线及继任义务；依赖：9.5；完成：3 件缺口已转 1 只能再转 2，补发同事务建立新制需求但不排任务；验证：`cd backend && ./mvnw -Dit.test=AfterSalesRepairRouteIT verify` 转绿并通过已有模块边界测试。
- [ ] 9.7 在 `T/integration/ReturnedPoolResponsibilityIT.java`、`T/integration/AfterSalesReturnDispositionIT.java` 添加池内直接报废责任及 orders 主导处置的真实 MySQL 测试；依赖：9.6；完成：验证 orders 校验案例、有效义务、接收明细、版本及权限，production 只在调用方事务中处置；分别注入 orders 处置记录及 production 处置/责任/退出/移交写入失败，双方事实和数量全部回滚；成功重试不重复记录，并发不超可用量；不伪造任务/劳动/材料，非法归责证据拒绝；确认目标行为为红；验证：`cd backend && ./mvnw -Dit.test=ReturnedPoolResponsibilityIT,AfterSalesReturnDispositionIT verify`。
- [ ] 9.8 在 `B/orders/aftersales/application/AfterSalesReturnDispositionService.java`、`B/orders/aftersales/api/http/AfterSalesController.java` 实现公开 `AfterSalesApplicationService.disposeReturnedProduct` 的售后编排；通过 `B/production/api/ProductionAfterSalesReturnParticipant.java` 的 prepareDisposition/commitDisposition 调用 `B/production/application/ReturnedProductDispositionService.java`；依赖：9.7；完成：沿基线锁序原子保存 orders 处置记录和 production 处置、归责证据、业务日期、唯一退出及适用的消费/移交；关联同一命令及处置结果，不创建独立提交事务，不开放生产侧初次处置 HTTP；已有责任独立更正仍按基线保留；验证：`cd backend && ./mvnw -Dit.test=ReturnedPoolResponsibilityIT,AfterSalesReturnDispositionIT verify` 转绿并通过模块边界测试，真实 HTTP 归属及拒绝绕行在 13.7—13.8 复验。
- [ ] 9.9 在 `T/integration/AfterSalesCancellationIT.java` 添加 实现可用池、可逆路由、已路由终态及交付分支核对并保存服务端数量依据 的测试；依赖：9.8；完成：覆盖“10 件中 3 已移交、7 池内的取消只新增 7 件处置，旧终态不能重复解决缺口”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=AfterSalesCancellationIT verify`。
- [ ] 9.10 在 `B/orders/aftersales/application/AfterSalesObligationChangeService.java` 实现可用池、可逆路由、已路由终态及交付分支核对并保存服务端数量依据；依赖：9.9；完成：10 件中 3 已移交、7 池内的取消只新增 7 件处置，旧终态不能重复解决缺口；同时完成 `B/orders/aftersales/application/AfterSalesCostService.java`、`B/orders/aftersales/api/OrderAfterSalesCostSourceFeed.java`、`B/orders/aftersales/api/AfterSalesReportingFeed.java` 及案例 Query；售后费用确认更正与义务结案纳入 AfterSalesCaseIT，来源分页纳入 AfterSalesCancellationIT；验证：`cd backend && ./mvnw -Dit.test=AfterSalesCancellationIT verify` 转绿并通过已有模块边界测试。

## 10. 工资参考、逐项填写及核定

能力规范：`specs/payroll-settlement/spec.md`。

- [ ] 10.1 在 `T/integration/PayrollSourceIngestionIT.java` 添加 实现独立劳动和责任游标、完整更正批次及原新员工月份守卫更新 的测试；依赖：3.8、7.16、9.10；完成：覆盖“反向重放幂等，失败不越游标，原新月份版本原子更新”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=PayrollSourceIngestionIT verify`。
- [ ] 10.2 在 `B/finance/payroll/application/PayrollSourceIngestionService.java` 实现独立劳动和责任游标、完整更正批次及原新员工月份守卫更新；依赖：10.1；完成：反向重放幂等，失败不越游标，原新月份版本原子更新；验证：`cd backend && ./mvnw -Dit.test=PayrollSourceIngestionIT verify` 转绿并通过已有模块边界测试。
- [ ] 10.3 在 `T/integration/PayrollReferenceIT.java` 添加 实现工资期间、基础参考和材料/人工/预计分摊逐项成本行 的测试；依赖：10.2；完成：覆盖“未发生与缺依据区分，无自动扣款总额，客户供料及估算属性清楚”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=PayrollReferenceIT verify`。
- [ ] 10.4 在 `B/finance/payroll/application/PayrollCalculationService.java` 实现工资期间、基础参考和材料/人工/预计分摊逐项成本行；依赖：10.3；完成：未发生与缺依据区分，无自动扣款总额，客户供料及估算属性清楚；验证：`cd backend && ./mvnw -Dit.test=PayrollReferenceIT verify` 转绿并通过已有模块边界测试。
- [ ] 10.5 在 `T/integration/GlueAllowancePolicyIT.java` 添加 实现版本化自然月设置和全月参考运行，保持配置及来源指纹一致 的测试；依赖：10.4；完成：覆盖“拆期间不重复减免、缺配置不填零、迟到来源使待核定参考过期”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=GlueAllowancePolicyIT verify`。
- [ ] 10.6 在 `B/finance/payroll/application/PayrollGlueAllowancePolicyService.java`、`B/finance/payroll/application/GlueMonthReferenceService.java` 实现版本化自然月设置和全月参考运行，保持配置及来源指纹一致；依赖：10.5；完成：拆期间不重复减免、缺配置不填零、迟到来源使待核定参考过期；验证：`cd backend && ./mvnw -Dit.test=GlueAllowancePolicyIT verify` 转绿并通过已有模块边界测试。
- [ ] 10.7 在 `T/integration/PayrollConfirmationIT.java` 添加 实现逐人确认与可空人工处理明细，原子产生应付及内部人工成本命令 的测试；依赖：10.6；完成：覆盖“10 加 20 元处理项配 1000 元工资仍形成 1000 元应付及人工成本，无权限/错误引用拒绝，内部入账失败时核定和应付同事务回滚”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=PayrollConfirmationIT verify`。
- [ ] 10.8 在 `B/finance/payroll/application/PayrollConfirmationService.java`、`B/finance/costledger/application/DirectLaborCostService.java` 实现逐人确认与可空人工处理明细，原子产生应付及内部人工成本命令；依赖：10.7；完成：10 加 20 元处理项配 1000 元工资仍形成 1000 元应付及人工成本，无权限/错误引用拒绝，内部入账失败时核定和应付同事务回滚；验证：`cd backend && ./mvnw -Dit.test=PayrollConfirmationIT verify` 转绿并通过已有模块边界测试。
- [ ] 10.9 在 `T/integration/PayrollReviewAdjustmentIT.java` 添加已核定只读复核及人工差额调整测试；依赖：10.8；完成：复核不改原核定，处理明细引用保持独立，过期引用拒绝，纯金额调整不强制复核，调整后应付不得小于零或已付净额；本项不执行现金付款；验证：`cd backend && ./mvnw -Dit.test=PayrollReviewAdjustmentIT verify`，确认目标行为为红。
- [ ] 10.10 在 `B/finance/payroll/application/PayrollReviewService.java`、`B/finance/payroll/application/PayrollAdjustmentService.java` 及对应复核/调整 HTTP 适配层实现只读复核及有原因的人工差额调整；依赖：10.9；完成：只改变明确的工资应付和人工成本差额，不写现金、不提供付款入口；验证：`cd backend && ./mvnw -Dit.test=PayrollReviewAdjustmentIT verify` 转绿并通过已有模块边界测试。

- [ ] 10.11 在 `T/integration/PayrollPaymentParticipationIT.java` 添加内部支付资格及关联的测试；依赖：10.10；完成：调用方本地事务中校验已核定状态、稳定应付锁序及可付余额，多人任一失败整体回滚，调用方回滚不残留支付关联；仅测试内部契约，不借测试调用方模拟已完成真实付款验收；验证：`cd backend && ./mvnw -Dit.test=PayrollPaymentParticipationIT verify`，确认目标行为为红。
- [ ] 10.12 在 `B/finance/payroll/api/PayrollPaymentParticipant.java` 和 `B/finance/payroll/application/PayrollPaymentParticipationService.java` 实现内部支付资格及关联能力；依赖：10.11；完成：加入调用方同一事务，不依赖 cash 的实现或表，不单独创建现金或开放 HTTP 付款/已付状态入口；同时完成 `B/finance/payroll/api/PayrollReportingFeed.java` 及工资期间/参考/复核/月减免 Query，在 PayrollReviewAdjustmentIT 验证读模型与只读核定历史；验证：`cd backend && ./mvnw -Dit.test=PayrollPaymentParticipationIT verify` 转绿，真实付款留到 12.1—12.2 验证。

## 11. 成本台账和归集

能力规范：`specs/cost-ledger/spec.md`。

- [ ] 11.1 在 `T/integration/MaterialCostAccrualIT.java` 添加 实现实际完成量与最终供料依据归集，只取当前工序工作室非人工成本 的测试；依赖：8.10、9.10、10.10；完成：覆盖“客户供料覆盖不入工作室成本，修复正常归集，重复来源不重复入账”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=MaterialCostAccrualIT verify`。
- [ ] 11.2 在 `B/finance/costledger/application/ProductionMaterialCostAccrualService.java` 实现实际完成量与最终供料依据归集，只取当前工序工作室非人工成本；依赖：11.1；完成：客户供料覆盖不入工作室成本，修复正常归集，重复来源不重复入账；验证：`cd backend && ./mvnw -Dit.test=MaterialCostAccrualIT verify` 转绿并通过已有模块边界测试。
- [ ] 11.3 在 `T/integration/LaborAndAfterSalesCostIT.java` 添加 复用 10.8 的实际人工入账服务并实现售后正式成本下游归集，不合并事实所有权 的测试；依赖：11.2；完成：覆盖“人工仅取实际核定，超额参考与处理明细不加减成本，生产写不被归集失败回滚”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=LaborAndAfterSalesCostIT verify`。
- [ ] 11.4 在 `B/finance/costledger/api/CostLedgerApplicationService.java` 复用 10.8 的实际人工入账服务并实现售后正式成本下游归集，不合并事实所有权；依赖：11.3；完成：人工仅取实际核定，超额参考与处理明细不加减成本，生产写不被归集失败回滚；只消费 9.10 已完成的售后成本 Feed，不在 finance 阶段反向实现 orders 的来源能力；验证：`cd backend && ./mvnw -Dit.test=LaborAndAfterSalesCostIT verify` 转绿并通过已有模块边界测试。
- [ ] 11.5 在 `T/integration/CostCorrectionAllocationIT.java` 添加 实现历史金额精确反向、替代、显式分摊及失败恢复 的测试；依赖：11.4；完成：覆盖“冲正不取新价格，分摊合计守恒，订单缺人工分摊显示不完整”及失败路径，并确认目标断言为红；额外覆盖权限拒绝、恢复后不双计、失败批次水位不前移；不以可见延迟替代可操作恢复；验证：`cd backend && ./mvnw -Dit.test=CostCorrectionAllocationIT verify`。
- [ ] 11.6 在 `B/finance/costledger/application/CostCorrectionService.java`、`B/finance/costledger/application/CostAllocationService.java` 实现历史金额精确反向、替代、显式分摊及失败恢复；依赖：11.5；完成：冲正不取新价格，分摊合计守恒，订单缺人工分摊显示不完整；补齐 `B/finance/costledger/application/CostAccrualRecoveryService.java`、`B/finance/costledger/api/CostReportingFeed.java`、成本及失败队列 Query；retryFailure 复用归集逻辑和权限/审计，不跳过来源；为 12.4 提供同事务经营费用入账能力并以调用方回滚测试验证，不提前测试现金；验证：`cd backend && ./mvnw -Dit.test=CostCorrectionAllocationIT verify` 转绿并通过已有模块边界测试。

## 12. 真实现金、凭证和部分退回

能力规范：`specs/cash-management/spec.md`。

- [ ] 12.1 在 `T/integration/CashSourcePaymentIT.java` 添加订单、售后、工资收付和逐人/多人部分工资付款的真实事务测试；依赖：8.10、9.10、10.12、11.6；完成：通过 cash 命令/HTTP 入口支付；1000 元应付付款 400 元后现金流出 400、已付 400、待付 600；多人任一超额、关联失败或现金保存失败全部回滚，首次来源也受守卫保护，幂等重试不重复付款；验证：`cd backend && ./mvnw -Dit.test=CashSourcePaymentIT verify`，确认目标行为为红。
- [ ] 12.2 在 `B/finance/cash/application/CashApplicationService.java` 和 `B/finance/cash/api/http/CashController.java` 实现订单、售后及工资实际收付，包含 `payPayroll` 的逐人/多人部分付款；依赖：12.1；完成：cash 拥有事务和唯一付款 HTTP 入口，通过 10.12 的内部参与能力校验工资资格并原子保存现金、分配及工资支付关联，不重复核定或记人工成本；验证：`cd backend && ./mvnw -Dit.test=CashSourcePaymentIT verify` 转绿，并验证不能从 payroll HTTP 独立改变已付金额。
- [ ] 12.3 在 `T/integration/DailyCashRecordIT.java` 添加 实现方向、分类、业务日期、用途与可选计经营费用，不提供资金账户 的测试；依赖：12.2；完成：覆盖“未计费用不生成成本，收入不记经营费用，计费用失败现金同时回滚”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=DailyCashRecordIT verify`。
- [ ] 12.4 在 `B/finance/cash/application/DailyCashRecordService.java` 实现方向、分类、业务日期、用途与可选计经营费用，不提供资金账户；依赖：12.3；完成：未计费用不生成成本，收入不记经营费用，计费用失败现金同时回滚；验证：`cd backend && ./mvnw -Dit.test=DailyCashRecordIT verify` 转绿并通过已有模块边界测试。
- [ ] 12.5 在 `T/integration/CashVoucherIT.java` 添加 实现全部现金类型可选凭证以及归本条现金的正式文件引用 的测试；依赖：12.4；完成：覆盖“空附件可提交，错误引用回滚现金，原付款和退回凭证不混同”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=CashVoucherIT verify`。
- [ ] 12.6 在 `B/finance/cash/application/CashVoucherService.java` 实现全部现金类型可选凭证以及归本条现金的正式文件引用；依赖：12.5；完成：空附件可提交，错误引用回滚现金，原付款和退回凭证不混同；验证：`cd backend && ./mvnw -Dit.test=CashVoucherIT verify` 转绿并通过已有模块边界测试。
- [ ] 12.7 在 `T/integration/PayrollCashReturnIT.java` 添加 实现按原员工支付分配的真实部分退回、上限与跨月现金日期 的测试；依赖：12.6；完成：覆盖“9 月 2000 元甲乙付款、10 月甲退 100 的案例准确且不自动减少工资成本”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=PayrollCashReturnIT verify`。
- [ ] 12.8 在 `B/finance/cash/application/PayrollCashReturnService.java` 实现按原员工支付分配的真实部分退回、上限与跨月现金日期；依赖：12.7；完成：9 月 2000 元甲乙付款、10 月甲退 100 的案例准确且不自动减少工资成本；验证：`cd backend && ./mvnw -Dit.test=PayrollCashReturnIT verify` 转绿并通过已有模块边界测试。
- [ ] 12.9 在 `T/integration/CashReversalIT.java` 添加 实现错误现金整笔冲销、退回反向及经营费用成对反向 的测试；依赖：12.8；完成：覆盖“存在未冲销退回时原付款不得整笔冲销；并发不能双重返还额度”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=CashReversalIT verify`。
- [ ] 12.10 在 `B/finance/cash/application/CashReversalService.java` 实现错误现金整笔冲销、退回反向及经营费用成对反向；依赖：12.9；完成：存在未冲销退回时原付款不得整笔冲销；并发不能双重返还额度；补齐 `B/finance/cash/api/CashReportingFeed.java`、现金记录/分配/凭证 Query，验证整笔反向与原记录可追溯、真实退回不被当作经营收入；验证：`cd backend && ./mvnw -Dit.test=CashReversalIT verify` 转绿并通过已有模块边界测试。

## 13. 只读报表及月度汇总

能力规范：`specs/operating-reports/spec.md`。

- [ ] 13.1 在 `T/integration/ReportingProjectionIT.java` 添加 实现公开 Feed 的独立水位、租约、幂等及页面延迟状态 的测试；依赖：4.4、5.8、6.6、7.16、8.10、9.10、10.12、11.6、12.10；完成：覆盖“归集故障可定位且不阻塞业务，不跨模块 SQL 取数”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=ReportingProjectionIT verify`。
- [ ] 13.2 在 `B/reporting/application/ReportingIngestionService.java` 实现公开 Feed 的独立水位、租约、幂等及页面延迟状态；依赖：13.1；完成：归集故障可定位且不阻塞业务，不跨模块 SQL 取数；验证：`cd backend && ./mvnw -Dit.test=ReportingProjectionIT verify` 转绿并通过已有模块边界测试。
- [ ] 13.3 在 `T/integration/ReportingRebuildIT.java` 添加 实现影子投影重建、来源对账和原子切换 的测试；依赖：13.2；完成：覆盖“从空投影重建合计一致，失败保留旧可用版本，差异定位来源标识”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=ReportingRebuildIT verify`。
- [ ] 13.4 在 `B/reporting/application/ReportingRebuildService.java` 实现影子投影重建、来源对账和原子切换；依赖：13.3；完成：从空投影重建合计一致，失败保留旧可用版本，差异定位来源标识；完成 `B/reporting/api/ReportingAdministrationService.java` 的授权 rebuild/reconcile 入口及进度/差异查询；普通主题读取权限不授予管理权限；验证：`cd backend && ./mvnw -Dit.test=ReportingRebuildIT verify` 转绿并通过已有模块边界测试。
- [ ] 13.5 在 `T/integration/OperatingSummaryIT.java` 添加 实现生产/订单/成本/工资/现金报表与月汇总，区分应收、净收、成本和参考 的测试；依赖：13.4；完成：覆盖“报废移交不相加为 4，工资退回不充当经营收入，成本缺失时利润提示不完整”及失败路径，并确认目标断言为红；验证：`cd backend && ./mvnw -Dit.test=OperatingSummaryIT verify`。
- [ ] 13.6 在 `B/reporting/application/OperatingReportQuery.java`、`B/finance/cash/api/CashQuery.java` 实现生产/订单/成本/工资/现金报表与月汇总，区分应收、净收、成本和参考；依赖：13.5；完成：报废移交不相加为 4，工资退回不充当经营收入，成本缺失时利润提示不完整；验证：`cd backend && ./mvnw -Dit.test=OperatingSummaryIT verify` 转绿并通过已有模块边界测试。
- [ ] 13.7 在 `T/integration/BusinessHttpCoverageIT.java` 按 `delivery-contracts.md` 全部业务条目逐项添加真实 HTTP 测试；依赖：13.6、2.12；完成：经正式命令准备数据，读取列表/详情/设置/历史，验证授权、精度、幂等、冲突和回滚；付款仅 cash、内部 Feed/Participant 不成为路由，缺失适配行为为红；售后池初次处置仅由 orders 的 disposeReturnedProduct 接收，生产侧不存在绕过义务校验的网页路由；从合法订单售后入口准备来源，分别验证双方写入失败的整笔回滚；验证：`cd backend && ./mvnw -Dit.test=BusinessHttpCoverageIT verify`。
- [ ] 13.8 核对并补齐 `delivery-contracts.md` 已指定的各 owner Controller、Query 和请求响应类型；依赖：13.7；完成：每个清单条目均有实际路由及成功/拒绝用例；不把业务写入移到 platform，不以通用 CRUD 暴露内部表；验证：`BusinessHttpCoverageIT` 转绿、模块边界及后端全量 verify，通过后才接正式页面。

## 14. 管理端页面与真实接口

能力规范：`specs/management-ui/spec.md`，各页同时遵循对应业务规范；原型 HTML 只作为视觉参考。

- [ ] 14.1 在 `F/e2e/navigation.spec.ts` 与相关模块单元测试新增 统一导航、认证守卫、错误处理和十进制金额契约 的场景；依赖：13.8、2.12；完成：通过正式 API 准备数据，未实现时目标行为为红；验证：`pnpm --dir frontend exec playwright test navigation.spec.ts`，不得直接写数据库准备业务。
- [ ] 14.2 在 `F/src/app/App.vue`、`F/src/router/index.ts`、`F/src/platform/services/apiClient.ts` 及约定的 composable/service/contract 实现 统一导航、认证守卫、错误处理和十进制金额契约；依赖：14.1；完成：入口可达，未登录或无权用户被拒绝，服务端权限错误有清晰反馈；完成 `F/src/platform/pages/LoginPage.vue` 和 `F/src/shared/components/BusinessFileUpload.vue`，登录过期、上传失败及授权预览下载在 navigation.spec.ts 验证；各业务页引用同一组件且附件仍可选；验证：对应 Playwright 场景、模块单元测试、`pnpm --dir frontend typecheck` 和 `pnpm --dir frontend build`。
- [ ] 14.3 在 `F/e2e/customers.spec.ts` 与相关模块单元测试新增 客户列表、资料编辑、启停及历史引用展示 的场景；依赖：14.2；完成：通过正式 API 准备数据，未实现时目标行为为红；验证：`pnpm --dir frontend exec playwright test customers.spec.ts`，不得直接写数据库准备业务。
- [ ] 14.4 在 `F/src/modules/customer/pages/CustomerListPage.vue`、`F/src/modules/customer/pages/CustomerDetailPage.vue` 及约定的 composable/service/contract 实现 客户列表、资料编辑、启停及历史引用展示；依赖：14.3；完成：保存后重载仍显示服务器结果，冲突不静默覆盖，默认地址不被订单覆盖；验证：对应 Playwright 场景、模块单元测试、`pnpm --dir frontend typecheck` 和 `pnpm --dir frontend build`。
- [ ] 14.5 在 `F/e2e/products.spec.ts` 与相关模块单元测试新增 商品列表、详情、录入默认值/星级/工序选项及标准/自定义方案页面，售价保留而商品目标工序不出现 的场景；依赖：14.4；完成：通过正式 API 准备数据，未实现时目标行为为红；验证：`pnpm --dir frontend exec playwright test products.spec.ts`，不得直接写数据库准备业务。
- [ ] 14.6 在 `F/src/modules/product/pages/ProductListPage.vue`、`F/src/modules/product/pages/ProductDetailPage.vue`、`F/src/modules/product/pages/ProductEntrySettingsPage.vue`、`F/src/modules/product/pages/ProductEditorPage.vue`、`F/src/modules/product/pages/SchemeEditorPage.vue` 及约定的 composable/service/contract 实现 商品列表、详情、录入默认值/星级/工序选项及标准/自定义方案页面，售价保留而商品目标工序不出现；依赖：14.5；完成：完整商品可保存，非法人工覆盖不可提交，预算由后端返回；验证：对应 Playwright 场景、模块单元测试、`pnpm --dir frontend typecheck` 和 `pnpm --dir frontend build`。
- [ ] 14.7 在 `F/e2e/workforce.spec.ts` 与相关模块单元测试新增 员工就业资格、小组及标准维护，无绑定账号入口 的场景；依赖：14.6；完成：通过正式 API 准备数据，未实现时目标行为为红；验证：`pnpm --dir frontend exec playwright test workforce.spec.ts`，不得直接写数据库准备业务。
- [ ] 14.8 在 `F/src/modules/workforce/pages/EmployeePage.vue`、`F/src/modules/workforce/pages/TeamPage.vue`、`F/src/modules/workforce/pages/WorkforceStandardsPage.vue`、`F/src/modules/workforce/pages/ActivityReferencesPage.vue` 及约定的 composable/service/contract 实现 员工就业资格、小组及标准维护，无绑定账号入口；依赖：14.7；完成：活动引用阻止非法变更，离职返聘历史可追溯；验证：对应 Playwright 场景、模块单元测试、`pnpm --dir frontend typecheck` 和 `pnpm --dir frontend build`。
- [ ] 14.9 在 `F/e2e/production.spec.ts` 与相关模块单元测试新增 需求缺口、周排班/日负载、任务详情/调整取消、核验、质量处置/责任更正/事实更正、来源与移交记录、缺陷原因及订单追溯页面，三类结果和独立去向，以及无生产来源的其他任务安排/核验 的场景；依赖：14.8；完成：通过正式 API 准备数据，未实现时目标行为为红；验证：`pnpm --dir frontend exec playwright test production.spec.ts`，不得直接写数据库准备业务。
- [ ] 14.10 在 `F/src/modules/production/pages/TaskSchedulePage.vue`、`F/src/modules/production/pages/TaskVerificationPage.vue`、`F/src/modules/production/pages/ProductionTracePage.vue`、`F/src/modules/production/pages/DemandGapPage.vue`、`F/src/modules/production/pages/TaskDetailPage.vue`、`F/src/modules/production/pages/QualityDispositionPage.vue`、`F/src/modules/production/pages/ProductionCorrectionPage.vue`、`F/src/modules/production/pages/DefectReasonsPage.vue` 及约定的 composable/service/contract 实现 需求缺口、周排班/日负载、任务详情/调整取消、核验、质量处置/责任更正/事实更正、来源与移交记录、缺陷原因及订单追溯页面，三类结果和独立去向，以及无生产来源的其他任务安排/核验；依赖：14.9；完成：完成量不重复加移交，不出现未渲染模板，未完成量释放后可再次安排；OTHER_TASK 不强制选择生产来源，不展示虚假制品数量，区分可选商品计件依据；验证：对应 Playwright 场景、模块单元测试、`pnpm --dir frontend typecheck` 和 `pnpm --dir frontend build`。
- [ ] 14.11 在 `F/e2e/orders.spec.ts` 与相关模块单元测试新增 订单列表、已有订单接管、确认变更、发货、收退款及凭证入口 的场景；依赖：14.10；完成：通过正式 API 准备数据，未实现时目标行为为红；验证：`pnpm --dir frontend exec playwright test orders.spec.ts`，不得直接写数据库准备业务。
- [ ] 14.12 在 `F/src/modules/orders/pages/OrderListPage.vue`、`F/src/modules/orders/pages/OpeningOrderPage.vue`、`F/src/modules/orders/pages/OrderEditorPage.vue`、`F/src/modules/orders/pages/OrderDetailPage.vue` 及约定的 composable/service/contract 实现 订单列表、已有订单接管、确认变更、发货、收退款及凭证入口；依赖：14.11；完成：收付款只调 finance 命令，同一现金事实可回查，不生成重复来源；验证：对应 Playwright 场景、模块单元测试、`pnpm --dir frontend typecheck` 和 `pnpm --dir frontend build`。
- [ ] 14.13 在 `F/e2e/aftersales.spec.ts` 与相关模块单元测试新增 售后退回、原样退还、修复/补发、终态处置、义务路线变更、费用确认/更正、收退款赔付及结案 的场景；依赖：14.12；完成：通过正式 API 准备数据，未实现时目标行为为红；同时断言池内报废仅提交 orders.disposeReturnedProduct，不串行调用生产处置及售后补记；失败后两侧数量不变，重试成功后只存在一份关联处置及退出；验证：`pnpm --dir frontend exec playwright test aftersales.spec.ts`，不得直接写数据库准备业务。
- [ ] 14.14 在 `F/src/modules/orders/aftersales/pages/AfterSalesCasePage.vue` 及约定的 composable/service/contract 实现 售后退回、原样退还、修复/补发、终态处置、义务路线变更、费用确认/更正、收退款赔付及结案；依赖：14.13；完成：可用池与已路由终态分列，错误缺口转换明确拒绝，操作失败不显示假成功；售后收付仍仅调用 cash，费用确认不能直接形成现金，未完成义务禁止结案；池内初次处置只提交 orders.disposeReturnedProduct；生产事实由后端参与事务写入，不提供先处置再补记的页面流程；验证：对应 Playwright 场景、模块单元测试、`pnpm --dir frontend typecheck` 和 `pnpm --dir frontend build`。
- [ ] 14.15 在 `F/e2e/payroll.spec.ts` 与相关模块单元测试新增 逐项成本参考、胶水减免、人工处理金额、独立工资确认/复核、逐人/多人付款和人工调整 的场景；依赖：14.14；完成：通过正式 API 准备数据，覆盖付款只调用 cash、部分付款和多人上限、付款失败无已付残留、真实退回后人工调整不新增现金；未实现时目标行为为红；验证：`pnpm --dir frontend exec playwright test payroll.spec.ts`，不得直接写数据库准备业务。
- [ ] 14.16 在 `F/src/modules/finance/payroll/pages/PayrollPeriodsPage.vue`、`F/src/modules/finance/payroll/pages/GlueMonthReferencePage.vue`、`F/src/modules/finance/payroll/pages/PayrollAdjustmentPage.vue`、`F/src/modules/finance/payroll/pages/PayrollEmployeePage.vue`、`F/src/modules/finance/payroll/pages/PayrollReviewPage.vue`、`F/src/modules/finance/payroll/pages/PayrollPaymentPage.vue` 及约定的 composable/service/contract 实现 逐项成本参考、胶水减免、人工处理金额、独立工资确认/复核、逐人/多人付款和人工调整；依赖：14.15；完成：处理金额不改工资，空/零区分，1000 元工资不自动减为 970 元，付款只允许已核定员工，调用 cash 的 payPayroll 正式入口；调整只调用 payroll.adjust，不提供单独修改已付金额的接口且不生成第二笔现金；验证：对应 Playwright 场景、模块单元测试、`pnpm --dir frontend typecheck` 和 `pnpm --dir frontend build`。
- [ ] 14.17 在 `F/e2e/finance.spec.ts` 与相关模块单元测试新增 日常收支、现金凭证、工资实际退回、错误现金冲销/来源分配明细及成本查询/汇总 的场景；依赖：14.16；完成：通过正式 API 准备数据，未实现时目标行为为红；验证：`pnpm --dir frontend exec playwright test finance.spec.ts`，不得直接写数据库准备业务。
- [ ] 14.18 在 `F/src/modules/finance/cash/pages/DailyCashPage.vue`、`F/src/modules/finance/cash/pages/CashHistoryPage.vue`、`F/src/modules/finance/costledger/pages/CostEntriesPage.vue` 及约定的 composable/service/contract 实现 日常收支、现金凭证、工资实际退回、错误现金冲销/来源分配明细及成本查询/汇总；依赖：14.17；完成：业务日期归月，凭证可选且归本条，现金与成本不混合；验证：对应 Playwright 场景、模块单元测试、`pnpm --dir frontend typecheck` 和 `pnpm --dir frontend build`。
- [ ] 14.19 在 `F/e2e/reports-settings.spec.ts` 与相关模块单元测试新增 月度现金成本分列、来源延迟、胶水配置版本和生效月、已授权账号角色维护 的场景；依赖：14.18；完成：通过正式 API 准备数据，未实现时目标行为为红；验证：`pnpm --dir frontend exec playwright test reports-settings.spec.ts`，不得直接写数据库准备业务。
- [ ] 14.20 在 `F/src/modules/reporting/pages/MonthlySummaryPage.vue`、`F/src/modules/platform-admin/pages/PayrollGluePolicyPage.vue`、`F/src/modules/platform-admin/pages/AccountRolePage.vue` 及约定的 composable/service/contract 实现 月度现金成本分列、来源延迟、胶水配置版本和生效月、已授权账号角色维护；依赖：14.19；完成：现金净额不叫利润，配置追溯修订提示历史核定不变，账号停用即时生效，设置命令仍归 finance.payroll；验证：对应 Playwright 场景、模块单元测试、`pnpm --dir frontend typecheck` 和 `pnpm --dir frontend build`。
- [ ] 14.21 在 `F/e2e/platform-operations.spec.ts` 添加审计检索、任务失败定位及授权重试/放弃场景；依赖：14.20、2.12；完成：经 API 生成审计和失败任务，未实现页面时断言为红，非管理员直接访问恢复接口被拒绝；验证：`pnpm --dir frontend exec playwright test platform-operations.spec.ts`。
- [ ] 14.22 在 `F/src/modules/platform-admin/pages/AuditSearchPage.vue`、`F/src/modules/platform-admin/pages/AfterCommitTasksPage.vue` 及约定四层文件实现检索与恢复入口；依赖：14.21；完成：显示来源标识、失败原因、重试状态；审计不可编辑，任务重试或放弃只调用 platform 管理命令，不修改原业务；验证：对应 E2E、typecheck 和 build。
- [ ] 14.23 在 `F/e2e/cost-operations.spec.ts` 添加归集失败定位/重试、成本更正与显式分摊场景；依赖：14.22、11.6；完成：覆盖无权重试、失败水位不前进、重试不双计、分摊总额不等拒绝和原金额冲正，未实现时断言为红；验证：`pnpm --dir frontend exec playwright test cost-operations.spec.ts`。
- [ ] 14.24 在 `F/src/modules/finance/costledger/pages/CostAccrualFailuresPage.vue`、`F/src/modules/finance/costledger/pages/CostAllocationPage.vue`、`F/src/modules/finance/costledger/pages/CostAdjustmentPage.vue` 及约定四层文件实现成本恢复、更正及分摊；依赖：14.23；完成：只调用 costledger 的授权命令，不编辑历史来源，不按参考工资自动摊人工；验证：对应 E2E、typecheck 和 build。
- [ ] 14.25 在 `F/e2e/report-operations.spec.ts` 添加主题权限、延迟、来源对账及影子重建场景；依赖：14.24、13.8；完成：未实现时断言为红，普通读取者不能触发重建，失败仍保留可读旧投影；验证：`pnpm --dir frontend exec playwright test report-operations.spec.ts`。
- [ ] 14.26 在 `F/src/modules/reporting/pages/ReportExplorerPage.vue`、`F/src/modules/reporting/pages/ReportReconciliationPage.vue`、`F/src/modules/reporting/pages/ReportRebuildPage.vue` 及约定四层文件实现主题报表、差异和重建管理；依赖：14.25；完成：按主题授权，显式显示版本/水位/延迟，管理命令只影响报表投影不改业务；验证：对应 E2E、typecheck 和 build。

## 15. 一期闭环、并发及交付证据

能力规范：全部 14 项；验收基线：`docs/一期/15-一期整体验收标准.md`。以下为实际测试/收尾，不是计划校验。

- [ ] 15.1 在 `T/integration/PhaseOneBusinessFlowIT.java` 串联客户/商品/人员、订单确认、排班核验、交付及收款完成；依赖：8.10、12.10；完成：每个权威事实与数量/金额可追溯且不依赖报表；验证：`cd backend && ./mvnw -Dit.test=PhaseOneBusinessFlowIT verify`。
- [ ] 15.2 在 `T/integration/AfterSalesFinanceFlowIT.java` 覆盖最近交付退回、修复报废转线下、仅缺口补发和售后现金；依赖：9.10、12.10；完成：无重复制作/处置/责任来源，移交不可逆，10/3/7 守恒；加入售后池直接报废的 orders 主导命令、错误义务引用及双端故障回滚，核对售后处置、生产处置、责任、唯一退出和移交引用一致；验证：对应真实 MySQL 集成测试。
- [ ] 15.3 在 `T/integration/PayrollManualDecisionFlowIT.java` 覆盖自然月多期间、迟到责任、空/零处理项、独立工资核定、复核和跨月部分退回；依赖：10.12、12.10、14.16；完成：实际金额不自动改变，历史决定可回查；验证：对应集成测试与 `frontend/e2e/payroll.spec.ts`。
- [ ] 15.4 在 `T/integration/ConcurrencyAndRollbackIT.java` 注入来源争抢、人员变更、核定/设置竞争、工资退回/冲销竞争及各参与端故障；依赖：7.16、9.10、10.10、12.10；完成：按固定锁序整体重试，无负数、超量、半笔现金或孤立凭证；验证：真实 MySQL 多线程测试，不以 H2 替代。
- [ ] 15.5 在 `T/integration/ReportingReconciliationIT.java` 验证重复消费、失败恢复、全量重建与应收/成本/现金对账；依赖：13.6、15.1、15.2、15.3；完成：差异为零或有可追溯未完整分摊提示；验证：来源逐项对账及影子版本切换测试。
- [ ] 15.6 在 `frontend/e2e/phase-one.spec.ts` 覆盖六模块及售后/设置/报表的真实页面链路、窄屏筛选布局、权限、空态、版本冲突和可选凭证；依赖：14.26、15.1、15.2、15.3、15.4、15.5；完成：无原型缓存依赖或未渲染模板；逐条遍历 delivery-contracts.md 的业务与管理入口，审计/任务恢复/成本失败/分摊/报表重建不可被月汇总页面替代；验证：Playwright 全量、类型检查与生产构建。
- [ ] 15.7 在 `backend/src/test/resources/migration/` 及 `docs/operations/phase-one-runbook.md` 完成备份副本迁移、恢复和已有订单接管演练，记录实际命令与结果；依赖：15.4、15.5；完成：不导入原型示例，恢复后的数量和账务对账一致；验证：独立测试库演练，未经授权不操作正式库。
- [ ] 15.8 逐条核对 `coverage.md` 与 `docs/一期/15-一期整体验收标准.md`，回写实际实现差异和验证记录；依赖：15.6、15.7；完成：`backend` 全量 verify、前端 unit/typecheck/build/E2E 和 OpenSpec 严格校验均有新鲜证据；验证：执行 design D8 全量命令、`python3 openspec/changes/implement-phase-one-business-system/verify-plan.py` 并保存真实输出；未满足项不勾选、不部署、不归档，后续提交与归档另获授权。
