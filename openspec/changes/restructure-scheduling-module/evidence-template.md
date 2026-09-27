# 排班模块通用 TDD / 证据模板

> 适用于 `restructure-scheduling-module` 细化后的 1–9 组任务。以 `tasks.md` 新编号及每项“证据契约 / 人工证据”为准，旧34项通过迁移表对应；每项在勾选 `[x]` 前必须回填实际证据。本次仅细化文档，尚未产生实现证据。

## 1. 证据条目结构

```markdown
- [ ] <编号> <任务原文>
  - 证据：Requirement/Scenario：<openspec/changes/restructure-scheduling-module/specs/<capability>/spec.md 的 Requirement 与 Scenario>；
    正式文档：<docs/architecture/... 或 change design.md 的章节>；
    文件：<实际新增/修改文件，含路径>；
    Flyway 版本：<本change重写的V1__yumi_v2_schema.sql，或“不适用（本项不改库）”>；
    API：<HTTP 方法/路径，或“不适用（本项无HTTP）”>；错误码：<稳定错误码，或“不适用（本项无拒绝）”>；
    表：<涉及的表，或“不适用（本项无持久化）”>；事务拥有者/锁定对象/幂等键：<说明或不适用理由>。
  - 依赖状态：启动前置 <编号及范围>；核心就绪 <实际实现范围及证据锚点，或未就绪>；完整验收 <未完成/已满足，列出依据>。
  - 后置验收：<尚未运行/失败的方法及原因、所需生产者编号与真实造数路径、tasks.md集成批次中的回补时点；没有后置项则写不适用理由>。
  - RED：<行为断言命令>，退出码 <N>，关键失败原文：<必须是目标行为不存在的失败>。
  - GREEN：<命令>，退出码 <N>，<测试数> 个测试通过；关键断言：<至少三条行为断言原文>。
  - 回归：<阶段边界全量命令>，退出码 <N>，<总测试数> 个测试 <失败数> 失败。
  - 人工证据：不适用；或按第 3 节回填 humanVisualConclusion。
```

### 字段规则

1. RED 必须证明目标行为尚不存在；编译错误、缺依赖和环境错误不算行为 RED。
2. GREEN 必须记录命令、退出码、测试数和至少三条关键行为断言。
3. 不适用字段必须写理由，不能留空。
4. 命令必须可在 `backend/` 或 `frontend/` 原样复跑；数据库口令只通过外部 `YUMI_DB_PASSWORD=<钥匙串读取值>` 注入，不写入口令。
5. 前端项额外记录正式路由、操作入口、typecheck/test/build、浏览器快照、网络请求、控制台与 Electron 复用结果。
6. 预览路由和临时页面不能作为验收证据；只认正式 `/scheduling`、`/scheduling/tasks/...` 与订单售后上下文及HTTP黑盒结果。
7. 数据库/跨模块任务除测试输出外，必须记录真实事实行、来源/目标/用途、幂等键和失败事务无残留；不能只证明数组非空。
8. 核心就绪允许消费者按依赖矩阵继续施工，但原任务保持未勾选；局部GREEN注明实际测试方法，未运行或失败项逐项列出，不冒充整类/全量通过，不用禁用测试消除待验项。
9. 后置场景必须指向实际生产者和回补批次：1.5的Clock单测不代替4.3业务日期守卫；5.3授权→5.5等待计划→6.3显式取消终止必须走真实命令；5.7先退回入库核心，6.5/6.6/6.7后补无退回及合格转库；7组分别等待创建、读模型和取消/核验事实。
10. 局部算法/约束测试可在真实V1上使用限定ID的SQL夹具，但必须标明层级；不能把手造来源、用途调整、任务或核验事实用作缺失HTTP/服务生产者的端到端证据。完整验收须重跑任务规定的全部方法及适用回归。
11. 基线RED窗口必须早于改写V1及生产SQL调用：先明确旧库测试与重建各自授权范围，保持旧代码/旧V1执行可编译结构契约并保存断言失败，再改基线、授权重建取GREEN。Flyway checksum或上下文启动失败不是行为RED，不关闭validate或repair凑证据。
12. 5.1核心的原发货由真实订单确认→期初成品入库→普通库存领用→普通发货确认产生，记录真实shipmentItemId后才受理；此夹具由1.5定义、3.2/5.1接通，不等待加工合格或SQL插入已确认发货。
13. 路线证据必须列总量及有缝/无缝两项：本次补发显式录入、分次授权、路线gap拒绝、终止再授权及等总量换路线，不能只用总数相等证明守恒。4.3须验证同用途多路线按真实allocationId逐组核验，8.6核对分组载荷及错误，不以单用途为由省略多个路线份额；仅唯一有效份额可由服务端规范化。
14. C7整链证据必须预先创建制作5和装袋等待5，再用途拆3/2、核验3/1/1；记录既有source/task ID、physicalShareId/替代映射、失效事实、原计划5/有效4、未完成1/可重排0、终点预留3/库存1、无补做及历史资源各5。失效重放、未排失效、取消不复活和事务失败全回滚均需实值。

15. C8普通链必须走真实确认/ADD/增减/路线工艺变更/取消，记录新起因与旧total不变、终止/实物处置及全部关联计划；普通处置行不能塞售后FK。原发货两批消费真实可发份额、sourceLineId非0，作废/更正只恢复或传递该批自身原消费份额，不二扣库存；仅按既有状态及有效售后占用等条件限制，不能因原发货已消费或历史加工已开始而误禁。分别验证领用3→发货3→合法作废恢复可发3且库存不变，以及已关闭等量更正传递同份额；“已开始/已消费拒逆向”仅用于库存领用取消/接入冲销，两个命令的守卫不可混用。受理returnedSeamQuantity以该批实际份额为上限，与新补发seam分别断言。
16. C5两类完成证据必须分别实测：加工终点真实核验；期初成品真实领用头/行/原出库，无虚构终点核验。售后领用行由5.6真实命令生成，不SQL补假行；成品3领用→需求1/转库2，新批次2、预留1、原出库只3一次。现有reverseMovement合法逆向和已消费拒绝、与普通cancel防双恢复均取证；核验/需求处置入库的通用孤立冲销须拒绝。
17. C7补做两次报废须记录每次新physicalShareId及replaces映射，原等待source/task不增，最后合格才可执行；SCRAP_RETURNED未执行源1处置后balance0、processed0、terminated0；非终点无任务实物转库存仍须后续加工；工艺A/B不能仅靠boolean兼容。覆盖、资源、实物前沿分别取非零实值。
18. C9须先有1.1用户明确评审，再记录已开始B与后建更早A不抢量、后到流入优先补已开始剩余、核验释放有效未完成；保护绑定只能引用已存在flow，不能用未来预占或真实时钟凑行为。
19. C10五项门禁分别留证：Modulith编译DAG、端口签名/导出边界、禁循环/禁lazy真实Bean启动、同线程同事务故障回滚、整批统一L锁序。确认/退回/领用/处置/入库各失败点均核对跨模块无残留；没有偶遇死锁不等于锁序正确，没有import环不等于Bean无环。内部写端口脱离事务须拒绝，不以mock/no-op替代真实适配器。
20. 文档闭环复核列生产者→消费者→正向事实→取消/作废/冲销/更正→终态守卫→任务依赖→验收前置，发现同范围缺口需修复再复核。严格格式校验、标题覆盖与独立审查分别记录；均不替代未来应用测试、破坏性授权和用户视觉签字。
21. `mixedOrderChangeUsesOneLockPlan`（3.1及实物核心回补）须真实同单 A成品转库+B无实物减量，兼顾ADD/增量混合行；记录全单一次 OrderSchedulingDispositionPort inspect/lockAndValidate/applyLocked、同一 Influence/LockedPlan、各层已取得的全部既有键及事务标识，证明不逐行重走L、不在库存锁后回锁owner/source、不调用Lifecycle.applyOrderChange。逐写点失败时核对需求/金额/映射/新源/旧份额处置/库存全回滚，不能只以无偶发死锁作证。
22. `twoSameProductAddsKeepDistinctOriginMappings`（3.1）须真实变更单中同商品双ADD，记录两条 order_change_item_applications 的 order_change_item_id、created_order_item_id、route_snapshot_id、新root/source及起因。route_snapshot_id是orders自有新明细冻结证据，另列adapter关联的排班routeSnapshotId与证据映射，不以相同字段名假设同一模块ID；证明不按商品/行号/查询顺序猜、不交叉建源，重放不增映射/授权，失败连新明细/快照/需求回滚。DAG/端口完整签名检查不得出现orders导入排班/库存类型。
23. `finishedInventoryRootIsQueryableButNotSchedulable`、`finishedAllocationThreeToOneUsesReturnedSourceIds`（3.2/5.6与6.7/8.8回补）必须经真实期初→领用→GET→减需请求：保存真实allocation head/line/原movementLine、INVENTORY_INFLOW root/source、physicalShare、purposeAllocation、终点flow及GET原响应；仅从GET提取sourceId/allocationId选择转库2，不SQL造锚点、不把领用行ID当sourceId。断言targetNode=SHIPPABLE、balance/executable恒0、无任务/核验/加工额度，terminalAvailableQuantity由3变1、预留1、新库2、原出库只3一次且旧库不恢复，Receipt/Coverage引用同一root及真实成品证据。普通fulfillment与售后case/scheduling-sources均可查询；表单过滤与服务端拒建任务分别取证。重放、故障回滚、转库争补发/冲销及合法逆向后的终点余额均须实值。
24. C3预录差异证据（5.4/8.7）：真实原发货混合路线→合法受理预录→verify-return显式提交不同的实际returnedQuantity/returnedSeamQuantity，不先correct。记录acceptedQuantity、预录两桶及原引用、实际两桶、扣除其他有效退回后的剩余份额、差异与本明细占用替代历史；允许实际总量/路线组成不同但仍0≤seam≤total≤accepted，分配/报废按实际两桶守恒，自身预录不双扣，其他有效占用和独立补发承诺不变。实际超accepted/某桶余量、并发占用、任一写点失败均整笔回滚；重放不重复差异/占用/来源，核验后correct只审计、不能逆向数量/来源/覆盖/库存。
25. C5期初真实生产者证据（1.5夹具、3.2核心、8.7界面）：从OpeningRequest/InventoryPage记录routeSeamRequired及正缝边合法seamTypeId，保留inventory自有冻结工艺/版本/分钟、node/seamState完成阶段与adapter兼容验证/排班快照映射。分别走无缝3→普通领用→普通发货和有缝指定合法工艺版本；缺/非法工艺、同boolean不同工艺不兼容应拒绝，混合库存分批，增量调整继承原证据。不得客户端填分钟、按目标订单回写批次、要求期初终点核验或SQL补造领用行；现有售后只movement line的缺口须由5.6真实头/行生产者关闭。
26. Receipt与逆向证据（3.2/5.6/5.7/6.7）：分别记录inventory自有OrderSurplusReceiptService的orderItemId/orderChangeItemId和AfterSalesInventoryReceiptService的afterSalesItemId/适用调整处置引用，不混售后FK；真实终点核验与成品领用证据互斥，先改用途后完成以核验分组为origin，当场转库以真实处置行为origin，originType/id/line防重且允许同领用行合法分次转库。核验/处置入库的通用孤立reverse返回STATE_CANNOT_CANCEL，独立期初/调整合法冲销仍成功；普通cancel与通用reverse同起因只恢复一次。记录同线程同事务及Receipt/writer不回调排班/上层服务的真实接线证据。

以上新增项均为待取证要求，不填成已通过；C9仍待1.1明确批准，tasks.md的59项任务不因本次文档同步勾选。规格仅补既有条款及THEN，保留39个Requirement和154个Scenario标题。

## 2. 命令速查

| 范围 | 命令 | 工作目录 |
| --- | --- | --- |
| 后端单测/集成 | `YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q -Dtest=<TestClass> test` | `backend/` |
| 后端全量回归 | `YUMI_DB_PASSWORD=<钥匙串读取值> ./mvnw -q test` | `backend/` |
| 前端类型检查 | `npm run typecheck` | `frontend/` |
| 前端单测 | `npm test` | `frontend/` |
| 前端构建 | `npm run build` | `frontend/` |
| 严格规格校验 | `openspec validate restructure-scheduling-module --strict` | 仓库根 |
| 差异空白检查 | `git diff --check` | 仓库根 |

每条命令都应在同一次调用记录退出码，例如：

```bash
<命令>; status=$?; printf 'EXIT=%s\n' "$status"; exit "$status"
```

管道末端命令的退出码不能冒充测试命令退出码。

## 3. 人工视觉结论

需要人眼判断的页面、浏览器端到端与原型对齐项必须附：

```yaml
humanVisualConclusion:
  status: pending-user-signoff
  checklist:
    - "正式 /scheduling 以日期横向周历为唯一主横向维度，其他排班与制作排班同级显示"
    - "正式新建页只有正常/返工/其他入口，没有超额或未来预占入口；制作工作量由服务端提示且不阻断提交"
    - "任务详情只读；批量核验按目标分配返工，不显示缝边报废输入"
    - "订单售后区域区分退回用途、目标、来源覆盖、真实库存去向和补发确认，不预置编辑表单"
  confirmedBy: null
  confirmedOn: null
  conclusion: null
```

用户明确确认前，`status` 保持 `pending-user-signoff`，不得因为截图或自动化通过而勾选任务。

## 4. 取证边界

- 当前文档状态仍为待评审/未实施，不能填入绿色测试数字或声称接口已存在。一次执行登记是本次审查新增设计，实施前须完成评审，不标为此前已确认决定。
- 补发隔离证据：原订购10/已发5/可发5、独立补发2，覆盖普通草稿修改/确认/作废/等量更正拒绝、专用归属拒绝、合法专用确认；同时核对原订单与售后非零台账、批次有效性、无普通恢复/替代事实以及合法物流未被误禁。
- 执行判定证据：当天无标记取消成功、开始后PENDING取消拒绝、正完成核验要求标记、未开始完全未做的完成0核验、开始与取消/减量竞争；核对不可逆登记审计，不把日期或PENDING当未执行证明。
- 无实物终止证据：未排和已排正常来源5→3，原授权5/终止2/有效3/覆盖3/库存0/报废0；已排显式取消制作与后续等待计划、保留原计划量、余3待重排；有实物或执行标记/遗漏关联占用拒绝，重放不双释放，投影从终止事实重建。
- 归档历史文档和已删除的旧 change 只作历史证据，不替代新命名下的浏览器证据。
- 测试先门禁再清库铺验收数据；任何清库、提交、推送和发布动作必须另行获得授权。
