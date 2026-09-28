# 排班模块重构实施清单

> 执行时使用 `superpowers:subagent-driven-development` 或 `superpowers:executing-plans`，逐任务执行行为 RED → 最小实现 → GREEN → 回填证据。本文直接作为 OpenSpec 的施工清单，不另建重复计划。**本轮仅细化文档；以下全部未实施、未验收、未获提交授权。**

**Goal：** 用统一排班承接正常加工、实际修补和其他工时，按来源守恒完成返工、制作补做、售后覆盖、库存终点与制作工作量提示。

**Architecture：** scheduling 管来源、任务与执行；orders 管普通履约和独立售后需求/覆盖；inventory 管批次和不可变流水。写入在同一数据库事务内按统一锁序完成，计划、加工额度、实物流入、历史资源和补发覆盖分别计算，不用一个总量代替。

**Tech Stack：** Java / Spring Boot / JdbcTemplate / MySQL / Flyway；React / TypeScript / Ant Design / Vite / Vitest。后端使用已有真实本机 MySQL 集成测试，保留 Testcontainers 暂缓豁免。

**Spec：** 本 change 的 `design.md`、`specs/scheduling-management/spec.md`、`specs/order-lifecycle/spec.md`、`specs/inventory-management/spec.md`；施工依据为 `docs/architecture/scheduling-module-design.md`、`after-sales-module-design.md`、`domain-and-quantity-model.md`、`database-design.md`。旧生产文档和归档只作历史，不当新实现证据。

## 全局边界、路径和执行约定

1. **已明确规则与实施边界：** 2026-09-27用户明确无开始任务/开工登记，未核验计划不论日期或现场加工均可取消，已核验不可取消；替代此前未获批准的执行登记/C9候选。1.1只核对文档和实施契约，不再等待业务拍板；本轮不实施、59项全部未勾选，视觉签字未完成。
2. **破坏性操作门槛：** 清库仅限另行确认的本机目标连接，不能根据默认库名自行执行。密码只由外部 `YUMI_DB_PASSWORD` 注入，不进入命令证据、仓库或回复。不执行提交、推送、发布，不恢复用户已有删除或覆盖归档。
3. **已确认规则不重开讨论：** NORMAL/REWORK；制作→制作、装袋→制作、缝边→制作/装袋；售后入口三种适用目标；缝边禁止报废；核验在任务日期后；未完成退同源但保留原日期资源；无超额、未来预占、REMAKE、自动任务或手工返工建源。
4. **路径简写是精确前缀：** `J/` = `backend/src/main/java/com/yumi/`；`T/` = `backend/src/test/java/com/yumi/`；`F/` = `frontend/src/`；`V1` = `backend/src/main/resources/db/migration/V1__yumi_v2_schema.sql`。后文 `J/scheduling/...`、`F/pages/scheduling/...` 是拟迁入路径，不宣称已存在。
5. **改名规则：** `J/production/` → `J/scheduling/`，类名 `ProductionTask*` → `SchedulingTask*`、`ProductionVerification*` → `SchedulingVerification*`、`ProductionNodes` → `SchedulingNodes`、`ProductionFlowService` → `SchedulingFlowService`、`ProductionCapacityService` → `SchedulingCapacityService`、`ProductionSnapshotReader` → `SchedulingSnapshotReader`、`ProductionReminder*` → `SchedulingReminder*`、`ProductionScrapController` → `SchedulingScrapController`、`ProductionQuantityReturn*` → `SchedulingQuantityReturn*`、`ProductionFact*` → `SchedulingFact*`、`AfterSalesProduction*` → `AfterSalesScheduling*`、`OrderProductionReference` → `OrderSchedulingReference`。`ReworkSource*`、`ScrapRecord*`、`OtherSchedule*` 保留类名但迁包。`J/calculation/production/` 同步迁至 `calculation/scheduling/`，对应 Production 前缀改 Scheduling。测试按相同规则改名；后文另标“新增”的测试不得冒充现有文件。
6. **测试命令：** `B(Class)` 表示在 `backend/` 执行 `./mvnw -Dtest=Class test`；`FTest(path)` 表示在 `frontend/` 执行 `npm test -- path`。每项填写具体 Class/path；同一命令先记录行为失败再记录成功。不存在新类型时，先以现有 HTTP/SQL 断言或可编译的契约外壳取得 RED，编译/缺依赖/连库失败不是行为 RED。
7. **事务公共契约 L：** 只读收集引用 → 所有者/来源上限行（售后受理先原发货明细）→ 任务头及明细 → 来源/覆盖余额 → 产品日期工序容量锁 → 库存批次；每层按稳定 ID/复合键排序，获得锁后重验归属、版本、余额。编号分配在上述业务锁之后且所有路径同序。任何失败回滚业务事实、占用、投影和库存；失败请求日志不当作业务残留。不得内部开启 `REQUIRES_NEW` 留半笔事实。
8. **HTTP 公共契约 H：** 全部要求管理员认证；写命令要求 `Idempotency-Key`、请求ID和业务审计，同键同请求重放首次结果，同键异请求 `CONFLICT_IDEMPOTENCY`。响应沿用 `{code,message,fieldErrors,requestId,data}`。GET 和精确 `POST /api/scheduling-tasks/preview` 不写业务事实/占用；预览保留认证、安全日志和请求ID，不写幂等记录及业务写审计。主要通用错误 `AUTH_REQUIRED`、`NOT_FOUND`、`VALIDATION_INVALID`、`CONFLICT_VERSION`；逐项补业务错误。
9. **逐项证据：** 每项的“证据契约”是待回填要求，不是已取得证据。写明实际文件/SQL/API、测试方法、RED/GREEN命令与退出码、断言实值、作用域内前后数据、幂等重放与失败回滚；不适用项写理由。UI 额外保留正式路由、截图、请求响应、控制台；其“人工证据”在用户确认前始终 `pending-user-signoff`，不得提前勾选。
10. **执行节奏：** 保留59个任务编号作为最终验收和追踪单位；跨任务闭环按下文集成批次推进，不要求每个编号都能独立完成全部验收后才启动下项。文档/实施契约核对、目标库重建授权、登录/人工签字和提交授权各守其门槛；已明确业务规则不再等待审批。全量门禁先于验收铺数；铺数后不得用清理共享库的测试破坏浏览器基线。
11. **依赖分层：** “启动前置”指设计、结构或调用契约可用；“核心就绪”指指定范围的真实实现及局部行为证据可供消费者接入；“完整验收”指该编号的全部测试、证据和适用人工签字满足。后文未另注明的“依赖”要求所消费的核心能力就绪，不要求生产者先取得后置整链证据。核心就绪不等于任务完成；局部GREEN必须列出方法范围及未通过/未运行项，不能称整类或全量通过，不能用空实现、常量或跳过失败测试凑出完成状态。具体回补生产者和时点见文末依赖表。

## 目标契约索引

### C1：工程物理模型（1.2 落定，尚未建表）

以下给出具体施工方案；1.2 须将逐列类型、FK、索引、CHECK 和锁行写入现有数据库文档，完成后再写 SQL，不把逻辑对象名称当已存在的表。

| 拟用表/字段 | 职责和关键约束 | 写入任务 |
| --- | --- | --- |
| `scheduling_sources` | 统一来源索引：owner_type、order_item_id、after_sales_item_id、source_kind、target_node、purpose、route_seam_quantity、route_no_seam_quantity、total_quantity、parent_source_id、root_source_id、round_no、origin_type/origin_id、version；固定目标及冻结路线份额，原总量不可覆写；同根可有多个工序授权但覆盖不按来源行相加 | 1.2、1.4、3.1、4.4–4.7、5.3 |
| `scheduling_route_allocations` | source_id、task_item_id、origin_flow_id、physical_share_id、route_snapshot_id、route_seam_required、quantity及消费/释放事实引用；计划授权分配与实际流入绑定分开，初始混合路线仍允许一条订单产品明细；C9所有未核验计划按同兼容池taskDate/itemId稳定分配，关联仅真实有效流入及消费/释放，不预占未来实物、不按现场加工保护 | 1.2、1.4、3.2、3.4、4.1、4.3–4.6 |
| `rework_sources` / `scheduling_quantity_returns` | 保留专属返工/报废补做追溯，分别以唯一 source_id 连接统一来源；前者关联核验分配，后者关联报废事实且目标只能 MAKING；不是同一来源只能安排一次；删除旧 `after_sales_production_sources` 物理表，售后来源Repository迁移后只查询统一sources及专属事实，不再保留第二套total/arranged权威量 | 1.4、4.4、4.7、5.3 |
| `scheduling_source_allocations` | source_id、task_item_id、planned_quantity；唯一 task_item_id，source_id 非唯一；PENDING占用和核验消费由任务/核验事实派生 | 1.4、3.4 |
| `scheduling_source_terminations` | source_id、purpose_allocation_id、origin_type/id/line、互斥order_change_item_id/after_sales_disposition_id及库存接入/订单取消起因、quantity与审计；唯一业务起因+source+份额，累计终止不得超可终止额，原授权不改，不强制普通/库存起因带售后adjustment_id | 1.4、3.1–3.2、6.2–6.3 |
| `scheduling_flow_entries` | 来源链、用途份额、from_verification_id、from_allocation_id、to_source_id、target_node、quantity、business_date；合格实物流入只写一次；允许原等待来源承接新流入，不把额度当实物 | 1.4、3.2、4.5–4.6 |
| `scheduling_rework_allocations` | verification_id、purpose_allocation_id、target_node、quantity；按核验+用途份额+目标唯一；入库前把单用途也规范化为非空用途份额引用，防 MySQL NULL 唯一键漏防重 | 1.4、4.4、6.6 |
| `scheduling_purpose_allocations` / `scheduling_verification_allocations` | 稳定physical_share_id、来源/任务映射、用途、单一路线、决定/调整引用、数量、替代关系；初始建源即生成非空用途份额，后续继承或追加替代；组核验唯一 verification_id+purpose_allocation_id；原份额与有效新份额不同时计数，既有等待链同步有效映射，详见C7 | 1.4、3.1、3.4、4.4–4.7、5.3–5.6、6.4–6.6 |
| `scheduling_source_invalidations` | scrap_record_id、target_source_id、physical_share_id、quantity及审计；三引用联合唯一，关联库存报废造成的下游未处理额度失效，不冒充授权终止或第二次报废；PENDING及未占用失效分开扣减，详见C7 | 1.4、4.7、6.6 |
| `after_sales_items.replacement_seam_quantity`及补发路线快照 | 用户明确录入本次补发缝边数量，CHECK范围0至补发总量；冻结合法工艺/分钟，需求及覆盖按有缝/无缝分桶，不能复制整单缝边量；调整保存两类前后值 | 1.4、5.1–5.3、6.1 |
| `scheduling_capacity_locks` | product_id+task_date+node 复合主键；无计划行时也有确定锁对象，不以空SUM代锁 | 1.4、3.3 |
| `scheduling_task_items` | 不设置开始登记或隐形执行字段；唯一核验引用；冻结分钟仍整数；来源、所有者、路线均可追溯 | 1.3、4.1–4.3 |
| `after_sales_coverage_entries` | 售后明细、root_source_id、份额引用、from_bucket/to_bucket、quantity、origin_type/origin_id/origin_line_id、request_id；唯一业务起因行；互斥覆盖桶 UNSTARTED/IN_PROCESS/RESERVED/SHIPPED/RELEASED；桶仅随真实授权/退回/库存接入/核验流转/发货/处置事实迁移，不表示任务开工状态，不由日期或现场加工推断、不用于取消资格；失败实物转 RELEASED 后新补做覆盖只登记一次 | 1.4、5.2–5.6、6.1–6.7 |
| `after_sales_return_allocations` / `after_sales_demand_adjustments` / `after_sales_demand_dispositions` | 退回核验用途/目标/数量；需求前后值/原因/操作者；dispositions以adjustment_id/source_id/allocation_id/action/quantity保存处置，并关联来源终止、用途迁移、库存；另以 `after_sales_disposition_task_items(disposition_id,task_item_id)` 保留显式取消集合；不使用 beforeValue/afterValue 文本代替结构化事实 | 1.4、5.4、6.1–6.7 |
| `shipments.shipment_kind` / `after_sales_case_id` | NORMAL/AFTER_SALES_REPLACEMENT；类型与售后归属一致；补发草稿创建即固化，全明细关联在同事务保存，不能混批 | 1.4、5.8–5.9 |
| 库存既有批次/流水的来源引用 | C5两种互斥完成证据、用途份额、普通order_change_item_id或售后after_sales_disposition_id及真实业务起因；不强制成品领用具有终点核验，不造退回FK，不绑定目标订单 | 1.4、3.2、5.6–5.7、6.7 |
| `inventory_allocations` / `inventory_allocation_lines` | 扩展现有头/行的owner_type与互斥订单/售后所有者，售后领用也真实生成行；行关联原出库movementLine、batch、routeSnapshot及份额；逆向事实关联原领用，不新建影子表 | 1.2、1.4、3.2、5.6 |
| 不可变路线快照及补做替代关系 | routeSnapshotId保存路线/具体缝边工艺/冻结分钟；physicalShare及purpose/flow引用；替代关系保存新旧share、scrapRecordId、quantity、前后allocation，承接等待而不复活废件 | 1.2、1.4、3.1、4.7、5.1、6.5 |
| `shipment_source_links` 及反向消费关系 | 真实终点可发份额、source_line_id非0、route_snapshot_id、route_seam_required、quantity；分批只消费未消费量，普通作废/更正按原份额反向/传递 | 1.4、3.2、4.5 |
| 普通订单变更份额处置、退回原份额关联 | 普通处置使用order_change_item_id及C8结构化dispositions；售后受理预录及核验实际returned_seam_quantity、选中原发货份额及工艺快照；实退差异追加前后占用替代历史，退回报废按实际路线落，不得与新补发seam混用 | 1.4、3.1、5.1、5.4 |
| `order_change_item_applications` | orders拥有order_change_item_id唯一→created_order_item_id及orders自有route_snapshot_id映射；双同商品ADD不混，适配器另保存排班快照映射，不反向FK到排班表 | 1.2、1.4、3.1 |
| `scheduling_sources.target_node=SHIPPABLE` 终点锚点 | 成品领用生成真实INVENTORY_INFLOW root/source及用途实物份额，无任务/加工额度；balance/executable为0，terminalAvailableQuantity由终点flow扣发货/转库/逆向独立派生，GET返回真实ID和C5成品证据 | 1.2、1.4、3.2、5.6、6.7 |

来源类型目标值为 `INITIAL_ORDER`、`REWORK`、`SCRAP_REPLENISHMENT`、`REPROCESSING`、`AFTER_SALES_NORMAL`、`INVENTORY_INFLOW`；用途为 `ORDER_DELIVERY`、`REPLACEMENT`、`INVENTORY`，与任务 NORMAL/REWORK 分开。数据库行ID仍内部 BIGINT；新任务编号工程目标为 `ST` + 6位序号、序列键 `scheduling_tasks`，1.2 检查现有编号空间无冲突后写入正式文档。其他已有库存/售后/发货编号前缀不改。

### C2：任务、来源和核验载荷

以下 TypeScript 形状是跨端目标契约；Java 使用同名字段的 record，所有数量服务端校验整数且非负，计划/授权数量必须正数。taskType、purpose、来源类型均不接受任意字符串绕过规则。

```ts
type Node = 'MAKING' | 'PACKING_BAG' | 'SEAM_CUTTING';
type TaskType = 'NORMAL' | 'REWORK';
type OwnerType = 'ORDER' | 'AFTER_SALES';
type Purpose = 'ORDER_DELIVERY' | 'REPLACEMENT' | 'INVENTORY';
type SourceKind = 'INITIAL_ORDER' | 'REWORK' | 'SCRAP_REPLENISHMENT'
  | 'REPROCESSING' | 'AFTER_SALES_NORMAL' | 'INVENTORY_INFLOW';
interface CreateTaskRequest {
  taskDate: string; employeeId: number; workTypeId: number;
  taskType: TaskType; note?: string;
  items: { sourceId: number; plannedQuantity: number }[];
}
interface ReworkAllocation { targetNode: Node; quantity: number }
interface PurposeResult {
  allocationId: number; qualifiedQuantity: number; reworkQuantity: number;
  scrapQuantity: number; reworkAllocations: ReworkAllocation[];
}
interface VerifyItem {
  taskItemId: number; qualifiedQuantity: number; reworkQuantity: number;
  scrapQuantity: number; reworkAllocations: ReworkAllocation[];
  purposeAllocations?: PurposeResult[]; note?: string;
}
interface VerifyRequest { items: VerifyItem[] }
interface SourceView {
  id: number; ownerType: OwnerType; orderItemId: number;
  afterSalesItemId: number | null; afterSalesCaseId: number | null;
  sourceKind: SourceKind;
  targetNode: Node | 'SHIPPABLE'; purpose: Purpose;
  routeSeamQuantity: number; routeNoSeamQuantity: number;
  totalQuantity: number; terminatedQuantity: number; pendingQuantity: number;
  processedQuantity: number; balance: number; executableQuantity: number;
  invalidatedQuantity: number; invalidatedUnoccupiedQuantity: number;
  purposeShares: {
    allocationId: number; physicalShareId: number; purpose: Purpose;
    routeSeamRequired: boolean; routeSnapshotId: number;
    quantity: number; invalidatedQuantity: number;
    balance: number; executableQuantity: number; terminalAvailableQuantity: number;
    completionEvidenceType: 'TERMINAL_VERIFICATION' | 'FINISHED_INVENTORY_ALLOCATION' | null;
    terminalVerificationId: number | null; inventoryAllocationLineId: number | null;
    inventoryMovementLineId: number | null;
  }[];
  rootSourceId: number; parentSourceId: number | null; roundNo: number;
}
```

原任务创建的 `orderItemId/sourceType/sourceId` 改为权威 `sourceId`；所有者、目标、用途从来源读取，不相信客户端声明。多订单来源可以共用一个头，但工序、员工、日期和类型必须一致；含售后来源必须走所属 case 的校验路径，普通入口拒绝售后来源。来源查询/订单可排数据须返回 SourceView.id，不能改完写契约却让前端拿不到来源ID。所有来源查询（包括全局返工列表）由服务端关联真实售后明细返回afterSalesCaseId；ORDER时两个售后ID均null，AFTER_SALES时两者必非空且互相匹配。统一新建从响应取得case而非依赖导航参数或猜ID；普通/售后不可混选，售后不得跨case，最终仍由服务端校验。

仅一个有效用途/路线份额时可省 `purposeAllocations`，服务端按持久化唯一有效allocationId规范化；多个有效份额必须完整提交，即使用途相同但路线不同也须分组，不得猜测各路线的合格/返工/报废结果。allocationId引用持久化purposeAllocationId，physicalShareId、用途和路线由服务端解析，不作为客户端可改写的核验字段。4.3交付静态多路线分组核心，6.6扩展用途替代及失效后的分组，不把初始路线守恒延后。混合组返工分配按目标聚合后必须等于明细 `reworkAllocations`，所有组的合格/返工/报废也必须分别等于明细总数；不能双写两套来源。路线份额由服务端持久化分配继承，不把单个boolean加到原本可含有缝/无缝件的一条订单产品明细上；初始分流保留现有冻结缝边数量规则，重过链只消费本链已记录路线，不能重新拿订单历史累计流入推断。

```ts
interface WorkloadHint {
  workdayHours: string; makingEffectiveHourRate: string;
  configurationVersion: { workdayHoursVersion: number; makingEffectiveHourRateVersion: number };
  scheduledMinutes: number; draftMinutes: number; totalMinutes: number;
  effectiveMinutes: string; differenceMinutes: string;
  conclusion: 'UNDER' | 'EXACT' | 'OVER';
}
```

比例沿当前设置的比率口径（75%为0.75），不是再除100；精确十进制响应使用字符串，整数计划/估算分钟使用number。Java WorkloadHint对应BigDecimal字段按字符串序列化，前端不把字符串转浮点后重判结论，只格式化显示。

### C3：售后写入载荷

```ts
interface AcceptAfterSalesItem {
  shipmentItemId: number; acceptedQuantity: number;
  returnedQuantity: number; returnedSeamQuantity: number;
  replacementRequiredQuantity?: number; replacementSeamQuantity: number;
}
interface AuthorizeNormalRequest {
  afterSalesItemId: number; quantity: number; seamQuantity: number; reason: string;
}
interface VerifyReturnRequest {
  afterSalesItemId: number; returnedQuantity: number; returnedSeamQuantity: number;
  allocations: {
    purpose: 'REPLACEMENT' | 'INVENTORY'; targetNode: Node;
    routeSeamRequired: boolean; quantity: number;
  }[];
  scrapQuantity: number; reason?: string;
}
interface DemandDisposition {
  sourceId: number; allocationId: number; quantity: number;
  action: 'TERMINATE_AUTHORIZATION' | 'CONTINUE_TO_INVENTORY'
    | 'SCRAP_RETURNED' | 'TRANSFER_QUALIFIED_TO_INVENTORY';
  cancelTaskItemIds: number[];
}
interface AdjustDemandRequest {
  targetType: 'REPLACEMENT_DEMAND'; afterSalesItemId: number;
  replacementRequiredQuantity: number; replacementSeamQuantity: number; reason: string;
  dispositions: DemandDisposition[];
}
```

AcceptAfterSalesItem为现有受理明细数量字段的目标扩展，其余既有问题/方案等字段保留。returnedSeamQuantity必填且0至returnedQuantity（退回0填0），按真实原发货份额扣除其他有效退回占用验证；服务端冻结原实物路线/工艺引用，与新补发快照分开。VerifyReturnRequest同时显式提交实际returnedQuantity/returnedSeamQuantity（0≤seam≤total≤acceptedQuantity）；5.4在核验事务重新锁原发货及售后明细，按实际两桶与原份额校验，追加受理预录到实际退回的前后差异，原子替换本明细未核验退回路线占用并保留旧引用历史。不得改变其他有效退回占用、不按补发路线猜实物、不额外改独立补发承诺；核验后禁止覆盖。现有更正只留审计不提供未核验数量更改生产者，不能假设其能先替核验改占用。每路线修补分配不超本路线实际退回，差额即该路线直接报废；两路线差额之和等于scrapQuantity。客户端不能用boolean改写冻结工艺。

沿用 `POST /api/after-sales/{caseId}/corrections`，由 targetType 分派，保留既有合法更正；不能接受通用文本改补发身份、已补发、来源余额或核验结果。减量影响已覆盖数量必须显式给出处置；未覆盖减量无需伪造处置行。同来源多份额以必填allocationId锁内解析具体用途及路线，拒绝重叠数量。正常授权返回 `SourceView`；退回/调整返回更新后的 `AfterSalesCaseView`，包含需求、已补发、预留、在途覆盖、未覆盖的总数及有缝/无缝分项、有效份额和结构化调整记录。

**补发路线生产契约（2026-09-27用户确认）：** 5.1受理请求明细与视图增加必填 `replacementSeamQuantity`，满足 `0 <= replacementSeamQuantity <= replacementRequiredQuantity`；补发为0时明确填0，补发总量仍仅在缺省时默认退回量。受理显式冻结本次补发两种路线数量及合法缝边种类/分钟快照，缺适用缝边工艺快照时拒绝正缝边数量；不能复制整单seamQuantity、按比例或优先级猜测。原退回实物适用路线与新补发承诺分开，退回分配的routeSeamRequired须符合实物路线，不能因新承诺改造其历史。5.2按有缝/无缝分别算required/shipped/reserved/active/gap，总数只作求和；退回补发分配、库存领用、正常授权均不得跨路线填补缺口。5.3的seamQuantity必填且在0至quantity之间，两类授权量分别不超对应gap，产生真实持久化份额及非空allocationId，供任务、核验和后续处置引用。需求调整同时提交新总量与其中缝边量，逐路线不得低于已补发；总量不变的换路线也是一类减少、一类增加，分别处置，不能按净总量跳过守卫。

### C4：逐端点施工矩阵

每行适用 H；Scenario 编号 S/O/I 的完整名称与场景覆盖见文末。业务拒绝在对应任务给出，非法路径资源组合返回 NOT_FOUND 或明确的 SOURCE_INVALID，不能只根据路径ID写入。

| HTTP 方法与目标路径 | 输入 → data 输出 | 后端任务 / 前端任务 | 规格场景锚点 |
| --- | --- | --- | --- |
| GET `/api/scheduling-tasks` | 保留dateFrom/dateTo/employeeId/workTypeId/taskType/status/orderId/productId，增加node/ownerType → TaskView[] | 2.2、3.8 / 8.1、8.2 | S2 多订单多产品任务；S23 统一新建 |
| GET `/api/orders`；GET `/api/orders/{id}`；GET `/api/orders/{id}/fulfillment` | 已确认订单筛选 / 详情 / 含每明细schedulingSources的履约视图 → 既有响应扩展真实来源ID | 2.2、3.1 / 8.3 | S2 多订单多产品任务；S7 三道工序不是三份客户需求 |
| GET `/api/scheduling-tasks/{id}` | id → TaskView（来源、快照、核验/取消事实、派生量、workload） | 3.8、7.3 / 8.5 | S3 核验保留原计划；S19 混合状态不误判 |
| GET `/api/scheduling-tasks/{id}/facts` | id → 按时间/类型/ID排序的事实数组 | 3.8 / 8.5 | S23 详情只读与批量提交 |
| POST `/api/scheduling-tasks` | C2 → TaskView；普通入口拒售后来源 | 3.4 / 8.3、8.4 | S15 创建任一来源不足全回滚 |
| POST `/api/scheduling-tasks/preview` | C2 → WorkloadHint，只读 | 7.2 / 8.3 | S20 提交前余量 |
| POST `/api/scheduling-tasks/{id}/items/{itemId}/cancel` | `{reason}` → TaskView | 4.2 / 8.2 | S15 取消返工释放同源；S18 已核验拒绝取消 |
| POST `/api/scheduling-tasks/{id}/verify` | C2 → VerificationView及派生头状态 | 4.3–4.7、6.6 / 8.6 | S17 多明细成功；S21 同明细混合用途核验按份额继承 |
| GET `/api/rework-sources`；GET `/api/rework-sources/{id}` | owner/target/purpose筛选或id → SourceView列表/单项 | 4.4 / 8.4 | S10 一源可跨任务安排 |
| GET `/api/scheduling-scraps`；GET `/api/scheduling-scraps/{id}` | 所有者筛选或id → 报废事实及补做sourceId | 4.7 / 8.5 | S14 装袋报废从制作补做 |
| GET `/api/scheduling-quantity-returns` | owner/target筛选 → 制作补做SourceView[] | 4.7 / 8.3 | S14 修补报废不回修补池 |
| GET `/api/scheduling-reminders`；POST `/api/scheduling-reminders/{id}/defer` | 未完成筛选 / 原有延期请求字段 → 未完成提醒 / 更新结果 | 3.7 / 8.2 | S18 十件计划只完成六件；S19 全取消与未完成提示 |
| GET `/api/other-schedules`；GET `/api/other-schedules/{id}` | 日期员工筛选 / id → 其他排班列表/详情 | 2.4 / 8.2、8.3 | S22 九十分钟其他排班 |
| POST `/api/other-schedules`；POST `/api/other-schedules/{id}/verify` | 原有员工/日期/事项/小时分钟 / 核验请求 → 其他排班详情 | 2.4 / 8.3 | S22 九十分钟其他排班 |
| POST `/api/other-schedules/{id}/cancel`；POST `/api/other-schedules/{id}/corrections` | 原有原因 / 更正小时分钟与原因 → 其他排班详情 | 2.4 / 8.2 | S22 工时更正留原值 |
| GET/POST `/api/orders/{orderId}/after-sales`；GET `/api/after-sales/{caseId}` | C3受理明细的退回/补发独立，必填returnedSeamQuantity与replacementSeamQuantity及各自冻结份额/路线；或id → CaseView列表/详情 | 5.1 / 8.7 | O1 部分发货即可创建售后；退回与补发不必相等 |
| POST `/api/after-sales/{caseId}/verify-return` | C3 → AfterSalesCaseView | 5.4 / 8.7 | O3 一次退回核验拆用途与目标 |
| GET `/api/after-sales/{caseId}/scheduling-sources` | caseId → SourceView[] | 5.3 / 8.4、8.7 | O6 未排返工来源已覆盖 |
| POST `/api/after-sales/{caseId}/scheduling-sources` | C3正常授权 → SourceView | 5.3 / 8.7 | O6 正常缺口须显式授权且终止额度不复活 |
| POST `/api/after-sales/{caseId}/scheduling-sources/tasks` | C2 → TaskView，共享创建服务且校验整批case归属 | 5.5 / 8.3、8.4 | O5 同源同目标拆分任务 |
| POST `/api/after-sales/{caseId}/corrections` | C3调整或既有合法更正 → AfterSalesCaseView | 6.1–6.7 / 8.8 | O8 全部减量/增量场景 |
| POST `/api/inventory/batches` | 既有OpeningRequest字段及必填routeSeamRequired、正缝边合法seamTypeId → 既有批次响应及冻结工艺证据；node/seamState仍表示完成阶段 | 3.2 / 8.7 | I3 期初成品领用三件减需一件转新库存两件 |
| POST `/api/inventory/after-sales-allocations` | `{afterSalesItemId,batchId,quantity,seamQuantity,reason}` → 原有流水ID/编号/类型 | 5.6 / 8.7 | I1 成品库存接入售后后确认补发；非终点库存还需后续加工 |
| POST `/api/after-sales/{caseId}/replacement-shipments` | `{items:[{afterSalesItemId,quantity,seamQuantity}]}` → AfterSalesCaseView | 5.8 / 8.9 | O2 普通入口不能修改或确认补发草稿 |
| POST `/api/after-sales/{caseId}/replacement-shipments/{shipmentId}/confirm` | 无body → AfterSalesCaseView | 5.8 / 8.9 | O1 售后补发确认；O2 专用确认必须验证整批售后归属 |
| POST `/api/orders/{orderId}/shipments`；PATCH `/api/orders/{orderId}/shipments/{shipmentId}`；POST 同路径 `/confirm`、`/void`、`/corrections` | 普通草稿每行quantity/seamQuantity、其余既有字段；确认/逆向按C8真实份额 → 普通响应；补发一律STATE_NOT_EDITABLE | 3.2、4.5、5.9 / 8.9 | O10 分批发货不得重复消费路线份额；普通作废和更正保留路线追溯；O2 补发隔离 |
| POST `/api/orders/{orderId}/change-orders`；PATCH `/api/order-changes/{id}`；POST `/api/order-changes/{id}/confirm` | 既有变更字段及C8 dispositions → 既有变更/确认响应扩展份额去向 | 3.1、4.2、4.7、6.5–6.7 / 8.8 | O9 普通增量及新增明细产生新来源；普通无实物减量终止关联额度；普通等总量路线变更不改历史实物 |
| POST `/api/inventory-allocations`；POST `/api/inventory-allocations/{id}/cancel` | 既有领用行新增seamQuantity及显式cancelTaskItemIds / `{reason}` → AllocationView；C8锁内校验来源与路线 | 3.2 / 8.7 | O10 库存领用后发货；O9 减单处理超出数量 |
| POST `/api/inventory/movements/{id}/reverse` | 既有`{reason}` → MovementView；按C8/C10协调相关普通/售后接入逆向，不新增专用取消入口 | 3.2、5.6 / 8.7 | I3 成品领用取消与转库互斥；领用已有下游消费拒绝取消 |
| PATCH `/api/orders/{orderId}/shipments/{shipmentId}/logistics` | 既有物流请求 → 既有响应，仅物流可变 | 5.9 / 8.9 | O2 只读物流和原发货不被错误隔离 |
| 旧 production/overtime 写读路径、POST `/api/rework-sources`、POST `/api/rework-sources/{id}/tasks` | 不保留别名或兼容重定向 → NOT_FOUND | 2.2–2.3 / 8.1、8.4 | S4 超额类型和端点被移除；S25 手工来源与事实删除不存在 |

普通来源选择复用已有 `GET /api/orders/{id}/fulfillment`：在 `J/orders/fulfillment/FulfillmentViews.java` 的每条 ItemFulfillment 增加JSON字段schedulingSources，Java使用orders自有 `OrderSchedulingSourceQueryPort.SchedulingSourceView`，字段映射C2；FulfillmentService调用orders自有查询端口，由scheduling适配器实现，不import排班类型（C10）。未确认订单不生成初始来源。前端现有listOrders/getOrder继续选订单与产品，再取履约视图选择sourceId；不在GET中补建来源，不新增顶级来源页。2.2/3.1通过完整Spring上下文验证“读到ID→创建任务”接线。

错误码固定：新增覆盖/正常授权超过未覆盖缺口用 `SOURCE_INSUFFICIENT`，可补发预留不足用 `AFTER_SALES_REPLACEMENT_INSUFFICIENT`，受理上限仍用 `AFTER_SALES_QUANTITY_EXCEEDED`；退回等式用 `AFTER_SALES_EQUATION_INVALID`，需求低于已补量用 `QUANTITY_INVALID`。跨case来源用 `SOURCE_INVALID`，不存在的路径资源用 `NOT_FOUND`，避免实施者用同一售后超额码表达不同边界。

其他排班精确请求沿现有Views：创建 `{scheduleDate,employeeId,hours,minutes,note}`，核验 `{hours,minutes,note}`，取消 `{reason}`，更正 `{hours,minutes,reason}`；列表筛选日期/员工，返回OtherScheduleView。未完成defer为 `{reason}`，表示“暂不安排”而不是变更任务日期。

### C5：新增跨模块接口的精确边界

这些签名是目标，不是现有代码。数据所有者提供的账/库存服务及DTO由数据所有者持有；反向请求端口的接口和全部DTO由请求方持有，scheduling仅实现适配，详见C10。Repository仍为模块内部；沿用现有公开FulfillmentLedger/AfterSalesLedger，不把内部Repository暴露给调用者。

```java
// J/orders/ledger/AfterSalesCoverageLedger.java
public record RouteCoverageView(boolean routeSeamRequired, int requiredQuantity,
                                int shippedQuantity, int reservedQuantity,
                                int activeQuantity, int uncoveredQuantity) {}
public record CoverageView(long afterSalesItemId, int requiredQuantity,
                           int shippedQuantity, int reservedQuantity,
                           int activeQuantity, int uncoveredQuantity,
                           List<RouteCoverageView> routes) {}
public record CoverageTransfer(long afterSalesItemId, long rootSourceId,
                               long purposeAllocationId, String fromBucket,
                               String toBucket, int quantity, String originType,
                               long originId, long originLineId) {}
public CoverageView coverage(long afterSalesItemId);
public void transfer(CoverageTransfer transfer);

// J/inventory/AfterSalesInventoryReceiptService.java
public record ReceiptRequest(long afterSalesItemId, long sourceId,
                             long purposeAllocationId, long productId,
                             String completedNode, String seamState, int quantity,
                             String originType, long originId, long originLineId,
                             String completionEvidenceType,
                             Long terminalVerificationId, Long inventoryAllocationLineId,
                             Long inventoryMovementLineId, Long demandAdjustmentId,
                             String reason) {}
public record ReceiptResult(long batchId, long movementId) {}
public ReceiptResult receive(ReceiptRequest request);
```

上面为接口签名清单，不是能单独编译的Java文件；实现需放入相应Service类。transfer读取请求审计上下文，不信任外部操作者字段；初次覆盖用明确的授权/退回/库存origin，迁移校验旧bucket份额充足，终止/报废走RELEASED。公开服务参加调用者事务并断言L规定的锁已按层取得，不能在receive中先拿库存锁再回锁售后。

ReceiptRequest区分业务起因与完成证据：加工库存用途终点以核验份额为origin，减量转库以真实调整处置行为origin并必填demandAdjustmentId。completionEvidenceType=TERMINAL_VERIFICATION时terminalVerificationId必填并关联当前份额到达终点的真实核验；FINISHED_INVENTORY_ALLOCATION时inventoryAllocationLineId和inventoryMovementLineId必填，关联实际成品领用批次、出库流水行和路线份额，terminalVerificationId为空，且只允许已有终点RESERVED的调整转库。两类互斥，不能全部为空，也不能以早期非终点库存接入代替后续终点核验。服务端解析并校验owner/source/allocation/产品/路线/数量一致，returned/rework引用从真实链查询，不要求不存在的退回ID。completedNode/seamState由服务端推导，库存入口复核不接受浏览器指定。库存唯一键仍取originType+originId+originLineId，部分转库不同处置行可分别入库，同一起因重放不增库；形成新批次，不恢复旧批次，不再扣原库存。已发生补发/转库/加工消费的原领用不得另行取消恢复库存；本change不新增售后领用取消HTTP。

普通订单余量使用同一库存底层入库核心，但不复用售后FK：新增 `J/inventory/OrderSurplusReceiptService.java`，提供 `receive(OrderReceiptRequest request)`，返回上述inventory自有ReceiptResult。OrderReceiptRequest与ReceiptRequest共享sourceId、purposeAllocationId、productId、completedNode、seamState、quantity、originType/id/line、completionEvidenceType、terminalVerificationId、inventoryAllocationLineId、inventoryMovementLineId、reason，所有者改为必填orderItemId，处置依据改为必填orderChangeItemId，不带afterSalesItemId/demandAdjustmentId。两种入口均由scheduling适配器调用，orders不得import库存类型；共用库存内部写核心及业务起因唯一性，普通处置行与售后处置行不可混用。普通已到终点份额可用上述两类真实完成证据；未到终点必须完成实际后续核验。对于先改变用途、后到终点的入库，origin为本次真实终点核验分组，仍追溯原orderChangeItemId/售后调整；当场合格转库的origin为处置行。

售后库存领用的现实现只有movement line，不能凭字段名假设已有allocation line。1.2/1.4扩展既有 `inventory_allocations` / `inventory_allocation_lines`，增加owner_type及互斥订单/售后归属；3.2/5.6在同一领用事务真实写入头、行、原出库movementLine和路线份额，返回及查询保留旧HTTP响应形状但可追溯真实ID。不得创建仅用于满足FK的伪领用行；普通/售后、非终点/终点都使用这套持久化事实。1.2核对当前列名后落定扩展映射，不保留第二套售后影子领用表。既有通用reverseMovement必须区分起因：未消费领用可同事务逆向新flow/source/coverage并回库一次；排班终点核验/需求处置生成的入库不得孤立冲销，返回STATE_CANNOT_CANCEL，本change不新增核验或处置撤销能力。独立期初/库存调整的既有合法冲销保留。普通cancel与通用reverse同一起因串行去重，不能各恢复一次。

**库存工艺证据生产者**：3.2同时扩展既有 `OpeningRequest` / 库存批次及 `F/pages/inventory/InventoryPage.tsx` 现有期初录入：保存真实routeSeamRequired和正缝边时所选合法seamTypeId，由inventory冻结不可变路线/工艺快照（字段与C7同语义，库存持有本域证据，不依赖scheduling DTO）；既有node/seamState仍校验已完成阶段。领用时由scheduling适配器读取库存证据并生成/关联排班routeSnapshotId，不能要求期初具有不存在的排班核验或以当前订单工艺反写批次。同一批次只表达一种真实工艺/路线；混合库存须分批录入，客户端不能自填标准分钟。期初无缝3→普通领用→普通发货与有缝指定工艺版本分支均纳入1.5真实夹具/3.2核心，前端归8.7；既有库存增量调整继承原批冻结证据、不改工艺。不能只增加routeSnapshotId消费者却漏期初生产入口。

**成品接入来源锚点**：3.2/5.6所有成品直接领用也在同事务生成 `scheduling_sources` 的真实INVENTORY_INFLOW根、physicalShare及purposeAllocation，不把inventoryAllocationLineId冒充sourceId。来源target_node允许终点SHIPPABLE作为追溯锚点，任务node仍只有三加工工序；终点锚点可安排/可执行恒0（业务不适用，不是假投影）、无加工额度/任务，total仅记录原接入追溯量，可处置终点余额用terminalAvailableQuantity单独由有效终点流入减发货/转库/逆向派生，不套加工balance公式。C2 SourceView.targetNode扩为 `Node | 'SHIPPABLE'`，各用途份额增加terminalAvailableQuantity及completionEvidenceType、inventoryAllocationLineId/inventoryMovementLineId/terminalVerificationId；非终点terminalAvailableQuantity为0，证据按C5实际分支提供。GET售后case/scheduling-sources及普通fulfillment返回这些真实锚点和可处置份额，创建表单过滤SHIPPABLE，服务端同样拒其建任务。售后成品根直接记RESERVED一次，普通直接可发；两者都是终点实物不是新制作授权。减量3→1前端从GET取得sourceId/allocationId/可处置2及成品证据，Receipt/Coverage使用同一真实source/root，不新增顶级来源页。SourceQuery DTO按相同JSON同步，测试 `finishedInventoryRootIsQueryableButNotSchedulable`、`finishedAllocationThreeToOneUsesReturnedSourceIds` 由3.2/5.6及6.7/8.8负责。

### C6：测试落笔示例与断言位置

例子用于说明“怎么写行为测试”，放入4.2的 `T/SchedulingTaskItemCancelConcurrencyTest.java`；利用1.5夹具先创建计划10，再于任务日期之后通过4.3真实HTTP核验完成0，把得到的taskId/itemId、登录sessionCookie传入。此例验证已核验拒绝取消；未核验三日期与现场已加工仍可取消另按4.1/4.2取证。若旧行为未拒绝则记录行为RED，再实现锁内守卫并复跑同一用例；不是删掉方法让编译失败。

```java
mockMvc.perform(post("/api/scheduling-tasks/{taskId}/items/{itemId}/cancel", taskId, itemId)
        .cookie(sessionCookie)
        .header("Idempotency-Key", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"reason\":\"取消测试\"}"))
    .andExpect(status().isConflict())
    .andExpect(jsonPath("$.code").value("STATE_NOT_CANCELABLE"));
assertThat(jdbcTemplate.queryForObject(
        "SELECT planned_quantity FROM scheduling_task_items WHERE id = ?",
        Integer.class, itemId)).isEqualTo(10);
assertThat(jdbcTemplate.queryForObject(
        "SELECT status FROM scheduling_task_items WHERE id = ?",
        String.class, itemId)).isEqualTo("VERIFIED");
assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM scheduling_verifications WHERE task_item_id = ?",
        Integer.class, itemId)).isEqualTo(1);
```

每个后端任务复制的是这种“真实请求＋本用例ID限定的事实断言”模式，而不是固定预期响应的mock。并发测试用CountDownLatch/ExecutorService启动两个独立事务，分别收集结果，再检查两者总量；前端接口测试沿现有vi.stubGlobal('fetch')方式断言序列化载荷，真实交互另外按8组浏览器步骤验证。每项列出的测试方法均为需要新增/改写的方法，不宣称当前已存在。

### C7：用途份额对既有等待链的承接与失效

- **初始生产者**：3.1统一建源核心持久化非空用途/路线份额及稳定physicalShareId，5.3授权、5.4退回、5.6库存及4.4–4.7子源均通过公开服务生成或继承。每个allocationId只对应一种用途和一条路线；同根各工序映射同一份额，不逐段增加覆盖。6.5仅追加替代份额，不是用途份额第一次落库。3.4计划授权分配引用持久化用途/路线份额，不把尚无实物的授权当实际流入；真实有效未消费流入另按C9在同兼容池按taskDate/itemId及流入份额ID稳定分配，读与核验锁内同算法，不绑定未来实物、不允许同一份额超占；SourceView/TaskView返回各份额的用途、路线、有效量、可安排及可执行量，原source.purpose仅是起始用途，不能覆盖混合有效用途。
- **用途调整传播**：6.5在L锁序内收集并锁定选中份额的全部来源和既有等待明细，为上游及下游尚未处理的对应份额同步追加替代映射。目标工序、路线、原计划量、日期、分钟及历史资源不变，不新建重复额度，不重算历史核验；已有下游消费的份额不能冒用上游在制分支。6.4未实际加工退回改用途复用同一份额服务；GET/任务创建/核验/回源均读取有效映射，不信旧单值purpose。
- **不补做报废的等待失效**：6.6/4.7对库存用途报废沿physicalShareId找到未处理下游额度，按 `scrap_record_id + target_source_id + physical_share_id` 唯一追加失效事实；不是6.2无实物授权终止，不在下游再造报废/补做，不再次释放覆盖。已经PENDING的原计划不自动取消或改量，失效部分可执行0、不能重新安排；尚未占用部分立即从可排剔除。只失效本件下游未处理份额，不以同root整链清零，不影响其它用途或路线。
- **余额与未完成分开**：`invalidatedUnoccupiedQuantity`是已失效且不再由PENDING占用扣除、从未在该目标处理的份额；`balance = totalQuantity - terminatedQuantity - pendingQuantity - processedQuantity - invalidatedUnoccupiedQuantity`。`invalidatedQuantity`记录全部失效量用于追溯，不能连同PENDING重扣。核验仍保留 `unfinished = originalPlanned - completed`，拆为 `returnableUnfinished + invalidatedUnfinished`，只将前者回可排；后者关联上游报废，保留已处置提示，不生成待重排提醒。取消仍释放原计划正常资源，但只能恢复有效份额的可排，不能复活失效量。
- **固定整链算例**：POST授权5无缝→GET来源→制作5与装袋等待5→现场实际加工但未核验→改补发3并将2转库存→制作核验补发合格3、库存合格1报废1。现场加工只是业务情境，不新增登记操作或字段。原装袋计划仍5，当前用途映射补发3/库存2，其中库存1失效，可执行3+1，未额外创建装袋额度。装袋核验4后原计划VERIFIED、未完成1但可重排0，终点补发预留3/真实库存1，库存报废1不补做；两条计划历史资源各5。重复请求不增份额/失效/库存；并发下游核验按L锁内重验并整笔回滚非法调整。另测未排等待源失效以及合法取消不会复活失效份额。

- **报废补做承接**：4.7生成新的实物份额而不复活报废份额，保存 `replacesPhysicalShareId`、quantity、scrapRecordId及前后allocation映射；旧root保留追溯不同时覆盖。按数量将旧份额尚未处理的等待source/task授权关联替换到新补做份额，保留旧等待ID/原计划资源、仍无流入，补做合格才流入；已处理段由4.6新建重过额度。补做再次报废追加下一次替代，不能使旧失败份额可执行。新增 `repeatedMakingScrapFeedsOriginalPackingWaitOnce`：制作1/装袋等待1→两次制作报废补做→最终合格承接原装袋1→终点1，覆盖始终1、等待授权仍1；与减量/取消竞争重验当前有效替代链。
- **实物直接处置失效**：6.4的SCRAP_RETURNED以及普通减单合法实物报废，除记录报废外须对当前未处理source及全部对应未处理等待份额追加失效事实，使用相同scrap+target+share防重及invalidatedUnoccupied余额；不能只处理下游、不能写无实物termination或冒充任务processed。处置要求所有占用已显式合法解除，已真实加工的实物不得冒用未加工直接处置分支；未核验计划可取消不改变该边界，不以执行标记或人工开工确认判定。新增 `disposedReturnedShareCannotBeScheduledAgain`：源1→需求0/报废1→balance0/processed0/terminated0，重放/取消/投影重建均不复活。
- **非终点实物统一用途转换**：6.5的CONTINUE_TO_INVENTORY适用于真实退回/库存接入/前段合格且当前未到终点的有效份额，不以当前存在任务或现场已加工为必要条件。无任务、待加工、实际在制均保留原阶段/路线，追加用途映射、解除一次对应覆盖，不提前入库、不设置开始字段或人工确认步骤。若当前份额已被下游消费，只能选择其当前有效后继，不改已处理上游历史。6.4保留未实际加工退回实物的合法直接报废分支，计划可取消不放宽该条件；6.7仅处理终点。
- **工艺快照身份**：每个物理/用途份额的routeSnapshotId绑定不可变路线、缝边种类及分钟版本；退回及其修补/重过继承原发货实物快照，新正常补发授权使用受理冻结的新制造快照，库存接入验证实际工艺兼容。有缝/无缝两桶仅展示汇总，兼容校验还比较具体工艺，不以同boolean允许工艺A代替B；本轮不新增改造或跨工艺替代许可，不兼容实物不能覆盖新承诺，可走已确认的库存用途/合法报废。新增 `returnedRouteSnapshotIsNotReplacementManufacturingSnapshot` 与 `sameSeamFlagDoesNotMakeDifferentProcessesCompatible`。

### C8：普通订单生命周期、原发货路线与逆向接线

- **普通订单确认与变更**：3.1同时修改 `J/orders/order/OrderService.java`、`J/orders/change/OrderChangeService.java` 与变更请求/视图。确认和ADD明细同事务冻结路线版本、生成初始各段来源；增加数量只为新增需求创建新起因来源，旧total不改。按路线比较before/after，10→12只新增2，10→8终止/处置2，总量10缝边4→2是有缝减少2、无缝增加2，不能按净总量跳过。新需求逐路线不低于有效已发；改变缝边种类同样比较工艺快照版本，已发及已实际加工实物不改历史工艺。新增份额冻结变更确认时合法快照，旧任务继续用旧快照。
- **减单处置**：普通变更不复用售后case或demandAdjustmentId。变更行增加 `dispositions[{sourceId,allocationId,quantity,action,cancelTaskItemIds,reason}]`，action限定TERMINATE_AUTHORIZATION/FINISH_TO_SURPLUS/SCRAP；原surplus总数字段只能作汇总，不能代替份额。优先终止确实无实物且未发生加工的多余额度，不能从未核验或计划取消推定该条件，相关已排计划须显式全部列出并合法整条取消，余量重排、不改原计划。对实际在制/成品保留已确认的继续完成转余量或合法报废选择，以当前实物前沿去重，不把making/packing/seam流入相加；现场已实际加工但尚未核验的实物不得伪造工序报废，须按正常核验落真实加工结果再走合法处置；未核验计划本身仍可显式取消，取消不替代核验、不消灭实物，也不新增执行标记或人工开工确认，缝边报废禁止仍有效。转余量追加INVENTORY用途及等待映射，终点实际入库、不再可发；报废同时退出订单用途，不再为已取消需求补做，下游失效按C7。每个减少路线的终止+实物处置须精确等于所减有效未发需求，变更金额/需求/来源/取消/用途/库存任一失败全部回滚。3.1先交付无实物变更，4.2/4.7、6.5–6.7交付通用份额与入库核心后回补实物分支，不等售后独有业务才定义普通契约。
- **终态与快照**：订单取消守卫按新任务/执行/库存/发货/款项事实检查；自动生成的未占用初始授权本身不冒充真实加工事实，无事实已确认订单合法取消时同事务终止全部授权。已取消/关闭订单不可再确认变更或新建ORDER_DELIVERY来源/计划，已存在合法余量INVENTORY链及独立售后不被误禁；关闭仍按原需求履约与结清条件，不能仅看新排班完成量。以现有公开端口读取/同步状态，不新增反向模块依赖。
- **原发货路线生产者**：3.2交付普通库存接入→可发份额→普通发货确认的最小链，4.5补加工终点来源。普通发货创建/改草稿每行增加必填 `seamQuantity`（0≤seam≤quantity），8.9同步 `F/pages/orders/ShipmentPanel.tsx`及普通发货API/表单测试，不仅改售后UI。草稿不占用；确认在owner锁内按所请求有缝/无缝与稳定可发份额ID消费当前未消费量，来源为真实库存领用行或终点核验份额，保存 `shipment_source_links` 的可发份额ID、路线/工艺快照、数量；sourceLineId不得写0。不从原始全部IN记录每批重新分摊，也不从订单缝边总量推断本批。源份额累计有效消费不得超原额；确认仍不二扣库存。
- **原发货逆向**：普通作废按原消费链接追加反向事实，仅恢复同份可发及路线余额；不恢复库存、不删除原链接，仍拒有效售后占用。已关闭等量更正仅在原权限/条件下原子撤销旧有效消费并将同路线/同来源份额绑定替代批次，不重新随机分配或改变路线；失败原批次保持有效。同键重放不重复释放。补发四入口隔离仍由5.9负责，不能用其禁止普通合法逆向。
- **退回实物路线输入**：5.1受理增加必填 `returnedSeamQuantity`（退回0也填0，范围0至returnedQuantity），与replacementSeamQuantity完全独立。以所引原发货的有效消费链接校验退回两类数量及扣除其他有效退回占用后的余额，锁原发货明细并持久化选中路线/工艺来源份额，混合路线不按比例猜。5.4修补分配仍显式routeSeamRequired；每路线修补不得超实际退回该路线，直接报废各路线量=该路线退回量−该路线修补分配合计，两者之和必须等于scrapQuantity，记录真实路线引用，不凭总报废隐藏错路线。受理总量上限及既有受理次数规则不变，不把退回+补发相加约束acceptedQuantity。用户新补发路线可以与退回不同，但不能改写退回工艺。
- **普通库存接入/取消**：沿现有AllocationRequest扩展为 `{orderId,reason,lines:[{batchId,orderItemId,quantity,seamQuantity,targetNode,cancelTaskItemIds}]}`，每行seamQuantity必填、范围0至quantity；取消/冲销仍只传reason，关联计划须先通过显式任务取消命令解除，不静默级联。3.2按领用真实路线份额同步跳过工序的授权扣减（追加终止事实）及接入后所需等待额度；同一份需求不同时保留初始制作额度和库存覆盖。已有受影响计划须走明确合法取消而非静默改量；已有执行/消费不得用领用覆盖第二次。保留既有合法取消领用命令：先锁owner/任务/来源/容量再库存，只有全部接入份额未真实加工、未被核验/下游/发货/转库或其他业务消费且相关计划已显式合法解除时才反向撤销接入、恢复库存一次，并以领用取消起因为仍缺需求生成新授权，不减少历史terminated或复活旧ID。售后领用不借普通取消入口绕过独立覆盖。该项由3.2定义、4/6组事实就绪后完成逆向回归。
- **数据落点**：1.2/1.4给普通变更处置行、初始授权变更起因、发货有效份额及反向链接、退回原份额引用落FK/业务键。普通与售后起因用互斥 `orderChangeItemId` / `afterSalesDispositionId`，不能把普通变更塞进售后外键；唯一键含originType+originId+originLineId+targetNode，根和路线分配防重。份额只表示可追溯数量分配，不要求逐件序列号。
- **验收生产者**：3.1/3.2新增 `T/OrderSchedulingLifecycleTest.java`、`T/ShipmentRouteLineageTest.java`，5.1/5.4扩展 `AfterSalesReturnAllocationTest`，6.7扩展 `AfterSalesQualifiedTransferTest`；覆盖ADD、10→12→8、等总量4→2、工艺版本变化、取消订单、领用/取消、两批路线、普通作废/更正和反向重放。5.1依赖3.2路线生产核心而非5.9隔离，完整逆向与实物减单后补，9.3通过正式订单变更/普通发货/售后入口完成整链。

### C9：无开始登记的未核验分配与取消边界（2026-09-27用户明确）

用户已明确没有显式开始任务/登记开工操作，此决定替代此前未获批准的执行登记及流入保护候选。不得新增开始方法、HTTP、UI、前置步骤、显式或隐形执行字段，也不得通过人工确认现场开工取得取消资格。所有未核验且未取消计划沿用原有同兼容池 `taskDate/itemId` 稳定顺序分配；兼容池按所有者、来源/目标、有效用途/实物份额及冻结工艺路线隔离，池内真实流入按稳定份额ID分配，不因现场是否加工改变优先级。首道MAKING无实物正常授权仍以合法授权余额执行，不强套下游实物流入限制。

3.2可执行读与4.3核验锁内重算必须使用同一算法：只分配当前真实有效且未消费流入，不重复分配、不绑定未来流入、不增加加工额度或覆盖、不生成完成事实。核验在任务日期之后、每明细一次，可完成0；正完成无需开始标记，但不得超计划与锁内可执行。核验消费实际完成份额、释放仍有效未完成占用，保留原计划与历史NORMAL资源；失效未完成按C7保留处置事实且不回可排。1.2/1.4的route_allocations只表达计划路线份额及真实流入分配/消费/释放关联，不引入开始保护优先级或开工状态。

4.1/4.2取消只检查未取消的PENDING及无核验事实，并按L与核验互斥重验；未来、当天、过期未核验均可取消，现场已实际加工也不据此拒绝计划取消。取消释放本计划有效来源占用及NORMAL资源，不消灭真实流入、加工或消费事实，不级联其他明细，不增加额度/覆盖，失效份额不复活。已核验拒绝；同键重放只返回首次结果。此规则只放开计划取消：未核验或取消绝不证明无实物/未加工，无实物授权终止仍须确实无实物且未发生加工；库存领用逆向与直接报废保留C7/C8的真实加工、核验消费、发货、转库等边界，不依赖执行字段，也不新增人工确认步骤；有实物继续原合法处置，普通生命周期及售后类型权限不变。

新增 `earlierPendingPlanUsesStableInflowPriority`、`laterInflowUsesSamePendingOrder`、`verificationConsumesCompletedAndReleasesValidUnfinished`：同兼容池来源10、真实流入5，先有B计划5，再创建日期更早的A计划5，未核验时重算A=5、B=0，无B保护；后到2仍按同序分为A=5、B=2，不能预支余3。A次日核验完成3、有效未完成2释放后，B可执行4（7−3），A历史资源仍5；后续另到3时B最多5、池中未分配2，无重复消费。另以4.1/4.2回归三日期取消、已实际加工未核验取消、已核验拒绝、取消/核验仅一方成功及无开始端点/字段，6.8回归取消计划不放宽库存逆向或直接报废。

### C10：无环模块端口、真实接线及同事务编排

**编译依赖与运行调用分开**：固定业务Java依赖为 `scheduling → orders`、`scheduling → inventory → orders`。禁止orders引用scheduling/inventory类型、inventory引用scheduling类型（包含全限定名、泛型、record分量、枚举）；不改变既有orders/inventory HTTP，不新建共享业务模块、不用Object/Map/JSON隐藏反向类型。数据库只读Reference的跨域SQL另列为数据耦合，不当Java无环证明。

| 拟新增端口（接口和全部DTO的所有者） | 调用方 | scheduling中的实现 | 方法及职责 |
| --- | --- | --- | --- |
| `J/orders/port/OrderSchedulingSourceQueryPort.java` | FulfillmentService | `integration/orders/OrderSchedulingSourceQueryAdapter.java` | `Map<Long,List<SchedulingSourceView>> sourcesByOrderItems(List<Long> orderItemIds)`；orders自有SchedulingSourceView/份额类型完整映射C2，批量只读、不建源 |
| `J/orders/port/OrderSchedulingLifecyclePort.java` | OrderService、OrderChangeService、AfterSalesService | `integration/orders/OrderSchedulingLifecycleAdapter.java` | `void initializeOrder(long orderId)`、`void cancelOrder(long orderId)`、`void verifyOrderClosure(long orderId)`、`void applyReturnVerification(long verificationId)`；确认/取消/关闭/退回按真实起因同步建源或守卫，不回调上层服务；普通变更全部归下一行端口，不提供另一个applyOrderChange写入口 |
| `J/orders/port/OrderSchedulingDispositionPort.java` | OrderChangeService | `integration/orders/OrderSchedulingDispositionAdapter.java` | `inspect(OrderDispositionRequest)` → Influence；`lockAndValidate(Influence)` → LockedPlan；`applyLocked(LockedPlan,long changeId)`；按C8整批普通份额处置，不用售后FK |
| `J/orders/port/AfterSalesSchedulingDispositionPort.java` | AfterSalesDemandAdjustmentService | `integration/orders/AfterSalesSchedulingDispositionAdapter.java` | `inspect(DemandDispositionRequest)` → Influence；`lockAndValidate(Influence)` → LockedPlan；`applyLocked(LockedPlan,long adjustmentId)`；按C3整批处置，含6.7库存入库 |
| `J/inventory/port/InventorySchedulingPort.java` | InventoryService | `integration/inventory/InventorySchedulingAdapter.java` | `allocateToOrder(AllocationRequest)` → AllocationView、`allocateToAfterSales(AfterSalesAllocationRequest)` → MovementView、`cancelAllocation(long allocationId,String reason)` → AllocationView、`reverseMovement(long movementId,String reason)` → MovementView；使用inventory自有DTO，普通/售后领用及相关逆向在拿batch锁之前进入编排 |

所有实现路径以上表 `J/scheduling/` 为前缀。每个端口可内嵌同模块record，不引用实现模块类型；OrderDispositionRequest含orderId、订单变更行和C8 dispositions，DemandDispositionRequest含caseId及C3 AdjustDemandRequest字段，Influence为请求及按L分组的既有资源ID/版本，LockedPlan为锁内解析的源、份额、数量、处置和版本；全部是服务端内部对象，不新增HTTP字段，不接受客户端“已加锁”标记。两个Disposition端口分别持有自己的record，不能跨普通/售后起因误用。所有普通订单变更行（ADD、无实物增减、路线/工艺变化、实物/取消处置）统一进入OrderSchedulingDispositionPort的一份Influence/LockedPlan，不调用独立生命周期变更写方法。先收集全单旧行/源/任务/库存引用并按L一次加锁，再写变更应用结果，最后applyLocked同时建新源及执行旧份额处置；不能沿现有逐行apply循环逐行各走一遍L。确认ADD时由orders在同事务持久化 `order_change_item_applications(order_change_item_id UNIQUE,created_order_item_id,route_snapshot_id)` 关联（UPDATE/REMOVE仍用原orderItemId）；此route_snapshot_id引用orders自有新明细冻结证据，不反向FK到排班表，适配器读取后生成/关联排班routeSnapshotId并保留证据映射，不假定跨模块本地ID相同。适配器按真实映射读新明细/快照，不按商品、行号或查询顺序猜；两条同商品ADD也须各自可追溯。新行无需补锁尚不存在的资源，但已有资源全部纳入先前锁计划。2.1落类和签名，3/5/6组补真实行为，不提供空实现冒充核心就绪。

- **导出**：新增orders/port与inventory/port的package-info并声明 `@NamedInterface("port")`；orders/ledger沿用已有导出，inventory根包暴露Receipt和底层 `InventoryAllocationWriter`。实现留scheduling内部，不开放跨模块Repository，不用OPEN、忽略违例或shared DTO绕过门禁。
- **Bean分层**：外层应用服务依赖本域端口；适配器只依赖排班核心及orders底层ledger、inventory底层writer/receipt；不回注OrderService/AfterSalesService/FulfillmentService/InventoryService。InventoryAllocationWriter负责库存锁定、校验和批次/流水/领用写入，不注入反向端口；receipt不回调排班。orders账服务只依赖本域repo。禁止以@Lazy或allow-circular-references规避注入循环。
- **事务**：外层命令开启事务，写端口及底层写服务经Spring代理以MANDATORY参与同线程同事务；禁止异步、提交后建源、内部HTTP、REQUIRES_NEW、独立连接提交、捕获异常继续提交。查询不强制写事务。整个批次只做一次inspect→owner锁→lockAndValidate其余L锁→真实业务起因写入→applyLocked，不逐行重走L、不循环调用外层取消服务。任何新发现的既有资源须整笔重新收集重试或拒绝，不能锁库存后回锁owner/source。所有编号序列在业务锁之后按固定序列键次序统一分配。
- **库证据与写入分工**：scheduling锁内解析源/份额/工艺/终点，inventory复核自身批次、领用、流水及防重；需要跨域只读证据时使用inventory自有Reference，不依赖scheduling服务，不跨模块写表。普通及售后转库由各自orders端口进入scheduling后调用C5对应库存服务；orders不直接import库存Receipt。inventory请求先协调owner/task/source/coverage/capacity，再调用writer取batch，不能先扣库再通知排班。
- **2.1/2.2前置门禁**：修改 `T/ModuleStructureTest.java` 九模块名单production→scheduling，保留calculation无出边及 `ApplicationModules.verify()`，新增DAG/端口签名/内部访问断言；扩 `T/YumiApplicationTest.java`，禁循环引用、禁懒初始化，真实全上下文启动且每端口唯一真实实现。模块校验与Bean启动分别取证，不用mock/no-op。确认→GET来源ID→创建必须真实贯通，GET重放无写入。
- **原子与锁序证据**：3.1确认/ADD建源失败回滚快照和需求，`mixedOrderChangeUsesOneLockPlan`覆盖A成品转库+B无实物减量同单，`twoSameProductAddsKeepDistinctOriginMappings`覆盖同商品双ADD且映射/源不交叉；3.2/5.6库存扣减、建源、覆盖各点故障整体回滚；5.4退回建源失败无核验残留；5.7/6.6终点写库失败核验回滚；6.1–6.7取消/终止/替代/解除预留/入库失败需求历史一起回滚。写端口无事务直调拒绝。独立事务+屏障测试多owner/多明细批次、领用争batch、调整争核验、取消争核验、转库争补发/现有冲销，记录L层次，不以“没有偶遇死锁”代替锁序证明。

## 1. 契约核对、物理基线与测试基础

- [ ] 1.1 核对已明确业务规则的文档与实施契约
  - 依据/依赖：2026-09-27用户明确决定、C9、S16、O5、O8；无前置。本项核对本change的proposal/design/tasks/specs；实施阶段同步 `docs/architecture/scheduling-module-design.md` §5.1等现行施工依据；本项为文档契约核对，无代码/SQL/HTTP写入。
  - 做法：①记录没有开始任务/开工登记、未核验不论日期或现场加工均可取消、已核验不可取消；②核对同兼容池taskDate/itemId稳定分配、读与核验锁内同算法、完成消费/有效未完成释放及失效不复活；③核验仍任务日期之后、一次、可完成0，正完成无开始前置；④逐项核对C1–C10、数据库/API/UI/测试/依赖与规格追踪，清除旧候选的字段、方法、端点和保护算法；⑤核对计划取消未放宽真实实物/加工/核验消费/发货/转库边界，无实物终止不能仅凭PENDING或取消状态，不新增隐形字段或人工确认。
  - 验收：`openspec validate restructure-scheduling-module --strict`；S16及O5/O8/I3与C9、实施契约及测试逐项一致；业务决定不再等待用户拍板，实施仍须完成各项交付与证据。
  - 证据契约：用户决定日期2026-09-27、受影响场景与文件行号、交叉核对及严格校验退出码；本项文档核对不伪造行为RED/GREEN，不把文档更新视为实现。
  - 人工证据：业务规则已明确，无需重开评审；实施契约核对与后续实施/验收、视觉签字尚未完成，全部59项保持未勾选，视觉状态仍为`pending-user-signoff`，不代签。

- [ ] 1.2 将 C1 逐表落定为可写 SQL 的字段和锁行设计
  - 依据/依赖：S2/S3/S5/S7/S10/S21/S24、O1/O2/O6/O8、I1/I2/I3；依赖1.1。文件：修改 `docs/architecture/database-design.md` §8/§9/§11/§12、本 change `design.md`；不新建重复设计文档。
  - 做法：①按C1写每列类型、是否空、默认、FK和索引；初始来源保留有缝/无缝数量份额，同一订单产品仍可一条任务明细，不为路线拆出用户未请求的重复明细；内部route_allocations锁定并传递份额，混合原订单汇总仍为10/10/4；②把普通订单确认/ADD/增减/取消的变更起因、原发货消费及作废/更正反向链接、退回路线与 `returnedSeamQuantity`、发货消费来源/工艺快照、两类Receipt完成证据的结构化引用和C9未核验稳定分配及真实消费/释放关联写入物理模型；③逐一指定核验→用途份额→返工目标、报废→补做、终点→库存、普通发货→可发份额消费/反向恢复的唯一业务键，聚合量由锁内服务守卫；④把owner、task、source、capacity、batch、shipment source line、terminal verification及finished inventory allocation映射到实际表主键，并列创建/取消/核验/普通变更/退回/减量/库存/补发各条锁序；⑤固定ST编号空间与统一来源ID，改正旧文档 `production_scrap_records` 为真实旧表 `scrap_records` 的迁入说明。
  - 新增物理落点：orders拥有的order_change_item_applications以order_change_item_id唯一关联created_order_item_id，订单侧冻结快照与排班快照的映射遵守C10，不能反向FK依赖排班表；成品SHIPPABLE来源锚点与加工Node分开，终点可处置量不套加工balance；实退两桶差异及预录占用替代保留前后引用。上述落点由1.4迁移测试验证，禁止用假sourceId/核验行满足FK。
  - 关键不变量：`balance = total - terminated - pending - processed - invalidatedUnoccupied`（C7失效且未被PENDING扣除的部分）；`processed = qualified + rework + scrap`；覆盖按root及实物份额而非每个工序source累加，并按路线分桶；普通订单按变更起因追加/终止而不覆写旧total，原发货按真实路线份额消费且反向只恢复同份额；两类Receipt证据互斥且不可缺失。逐行例算5授权终止2、计划10完成6、同源2+3、等待5上游失效1后核验4可重排0。
  - 验收：逐表字段/锁对象无悬空引用；CHECK不得跨表假装保证聚合；无`UNIQUE(source_id)`限制任务拆分；无空SUM锁；分钟INT保持，比例/有效分钟用精确十进制；普通变更、发货路线/反向、returnedSeamQuantity、C5Receipt两种证据及C9稳定分配及真实消费/释放关联均有明确列、FK/唯一键和业务起因。
  - 证据契约：逐表设计所在行、三组算式结果、各命令锁行顺序、编号冲突检索与规格校验输出；设计任务不伪造运行RED/GREEN。
  - 人工证据：非视觉；需复核1.1决定确已写入，物理方案不得改变已确认业务，不另索取业务重确认。

- [ ] 1.3 重写任务/核验 V1 基线并验证非法数据约束
  - 依据/依赖：S2/S3/S4/S13/S16/S17；依赖1.2。文件：修改V1；改名 `T/ProductionTaskMigrationTest.java` → `T/SchedulingTaskMigrationTest.java`。
  - 做法：①先准备可在旧代码编译的information_schema/非法INSERT结构契约测试；在1.5前置授权明确允许的旧库测试作用域内，保持旧V1与生产代码不变，运行并保存目标缺失的行为RED，清理仅限本测试数据；②取得RED后才改任务/明细/核验/提醒表及FK，无开始登记/隐形执行字段、任务类型CHECK、明细状态CHECK、每明细唯一核验；③把真实 `scrap_records` 改 `scheduling_scrap_records`；④删三张overtime表及关联FK，不增加兼容视图；⑤按1.5另行明确的重建边界迁移后跑相同契约取GREEN。不能改V1后连接旧库用Flyway checksum失败冒充RED，不关闭校验或repair掩盖；未授权不执行SQL。
  - 测试：`B(SchedulingTaskMigrationTest)`；`rejectUnsupportedTaskTypes` 拒OVERTIME/REMAKE；`noExecutionRegistrationColumns` 断言无开始登记或隐形执行字段；`rejectSecondVerification` 拒二次核验；`rejectSeamScrap` 拒缝边报废；新表存在、旧生产/超额表不存在。
  - 接口/事务：无HTTP；约束错误由后续服务翻译成稳定错误，不把MySQL异常文本暴露前端。FK需含父子来源顺序，不能禁FK后用脏数据通过测试。
  - 证据契约：V1差异、每条非法INSERT和SQLSTATE、information_schema实值、RED/GREEN命令退出码；本项未获库授权前不得勾选。
  - 人工证据：不适用（结构/约束自动验证）；破坏性运行授权归1.5，不能视为已获授权。

- [ ] 1.4 增加来源、份额、覆盖、终止与补发身份的关系约束
  - 依据/依赖：S10/S21/S24、O1/O2/O3/O6/O8、I1/I2/I3；依赖1.2，与1.3共用一次基线改写。文件：修改V1；新增 `T/SchedulingSourceMigrationTest.java`，修改 `T/AfterSalesMigrationTest.java`、`T/ShipmentMigrationTest.java`、`T/InventoryMigrationTest.java`。
  - 做法：①实现C1的来源/分配/实际流转/容量锁/用途及核验分组表；②普通订单确认/ADD/变更增减/取消的起因与处置、原发货路线消费/反向链接、退回路线与 `returnedSeamQuantity`、C9未核验稳定分配及真实流入释放/消费引用（无开始字段或保护优先级）一并落FK和唯一键；③售后退回分配、需求调整/处置、覆盖事件及补发身份加FK和唯一键；④库存业务起因关联允许无退回NORMAL链，但不允许缺终点或用途决定，并为C5Receipt的TERMINAL_VERIFICATION与FINISHED_INVENTORY_ALLOCATION分别保留互斥的强制证据链；⑤给每个可空FK说明合法分支，不以“全部可空”逃避追溯。
  - 新增结构验收：落实1.2的ADD应用映射、真实领用head/line/owner、inventory本域期初工艺证据、SHIPPABLE锚点及实退占用替代历史；新增 `persistsDistinctAddApplicationMappings`、`acceptsTerminalAnchorWithoutTaskVerification`、`preservesProvisionalAndActualReturnOccupancy`，明确终点锚点不生成任务、加工流入或假核验，终点可处置余额由服务计算。
  - 关键断言：`source5 -> allocation2 + allocation3`可落两条；同核验/用途/目标不可重复；同报废补做一次；同库存起因一次；NORMAL批次不能带售后case，补发必须带case；普通发货每条确认/反向只消费或恢复原路线份额一次；成品证据真实关联batch/allocation/movement/physicalShare，终点核验分支真实关联terminalVerification。跨行累计超额由3/5/6组服务负责，数据库测试不声称FK能保证它。
  - 测试：`B(SchedulingSourceMigrationTest,AfterSalesMigrationTest,ShipmentMigrationTest,InventoryMigrationTest)`；方法 `allowsSplitSourceAllocations`、`rejectsDuplicateOriginFacts`、`persistsNormalInventoryLineageWithoutReturn`、`enforcesShipmentIdentity`、`persistsShipmentRouteConsumptionAndReverseLinks`、`enforcesReceiptEvidenceAlternatives`。
  - 证据契约：列/键DDL、合法非零链SELECT结果、每项拒绝断言、RED/GREEN退出码；数据库约束与服务守卫责任分别记载。
  - 人工证据：不适用（真实SQL验证）；不以表存在替代来源FK和重复事实验证。

- [ ] 1.5 经授权重建本机单 V1 并建立固定业务时钟夹具
  - 依据/依赖：S16/S17/S24；授权检查前置于1.3/1.4旧基线RED，重建运行则等待两项SQL及2.1的SQL调用改名就绪。文件：修改 `T/support/ProductionFixture.java` 并改名 `SchedulingFixture.java`；新增 `J/shared/time/BusinessClockConfiguration.java`、`T/support/SchedulingClockTestConfiguration.java`、`T/BusinessClockTest.java`，修改相关测试日期和清理顺序。
  - 做法：①只展示目标host/port/database/username和允许重建边界，取得明确授权，密码外部注入；②按已授权范围重建，启动Flyway单V1和Hibernate validate；③生产注入 `Clock.system(ZoneId.of("Asia/Shanghai"))`，测试提供可控同区Clock，不靠`server.timezone`属性或系统默认时区；④共享夹具提供固定业务日与新API请求构造，清理按新FK逆序且限定本用例ID，不删全库业务数据；`createTask`、`cancelTaskItem` 的真实HTTP调用分别随3.4、4.2接通，不在本项伪造成功响应。
  - 测试：`B(SchedulingTaskMigrationTest,SchedulingSourceMigrationTest,BusinessClockTest)`；重复migrate无新版本、validate通过；`2026-10-05T15:59:59Z`对应上海10月5日、`2026-10-05T16:00:00Z`对应10月6日，切换测试Clock不依赖机器默认时区。既有固定2026-10-05任务日期不能靠真实当前日碰运气。
  - 取证窗口：先展示旧库测试和重建各自的连接/作用域并取得授权；保持旧V1/旧生产代码时运行1.3/1.4可编译结构契约RED，保存命令与断言失败；随后同批改V1/调用，在获准边界重建取GREEN。先重建会丢失旧基线行为，先改V1再跑Spring测试会在Flyway校验阶段失败，两者均不替代RED。
  - 夹具生产链：定义 `createConfirmedOriginalShipment`，返回真实orderId/orderItemId/shipmentId/shipmentItemId/batchId。以真实HTTP创建并确认订单→既有期初成品入库 `POST /api/inventory/batches` →普通库存领用到SHIPPABLE→普通发货创建/确认→读取shipmentItemId→5.1售后受理；本项只定义夹具结构，普通领用适配依赖3.2，5.1核心交付前接通验证，不等待4.5加工合格。不得SQL手造已确认发货或履约余额充当整链证据。
  - 验收边界：本项完成于获授权的迁移/启动、Clock日期边界和夹具基础验证；不要求未来核验端点已通过。真实订单/来源创建由3.1/3.4验收，原发货夹具随3.2/5.1验收，取消夹具由4.2验收，同日拒核验/本地次日允许由4.3验收；后续消费者失败回修本项，不反向阻塞时钟核心交付。
  - 接口/事务：时钟配置本身无业务写；迁移后业务测试用真实HTTP构造订单/来源，不手搭近似DDL。
  - 证据契约：授权记录、脱敏连接、迁移/启动退出码与flyway_schema_history、Clock边界断言、清理FK顺序和夹具作用域；环境错误单列，不冒充RED，不能把Clock单测写成核验接口已通过。
  - 人工证据：阻塞破坏性操作的用户授权；不需要视觉签字。

## 2. 命名切换、旧入口移除与其他排班保留

- [ ] 2.1 迁移后端包、SQL引用、计算器与跨模块入口
  - 依据/依赖：S1/S4/S5/S21；依赖1.2，和1.3/1.4组成一次可启动基线。文件：按路径规则迁移 `J/production/`、`J/calculation/production/`；修改 `J/orders/`、`J/inventory/`、`J/reports/ReportService.java` 的真实调用；同步既有Production前缀测试/夹具引用。
  - 做法：①先保留已有行为测试，批量迁包/类名/import；②逐条改Repository SQL表名、SequenceAllocator键和ST格式，非目标领域“production”业务文字不机械替换；③跨模块只调用公开入口，禁止orders直接读scheduling内部Repository；④修正 package-info 模块引用，核查启动bean不存在新旧双份；⑤在包/SQL切换后的最小Spring上下文中前移运行 `ModuleStructureTest`，明确验证无环依赖；按C10实现调用方自有端口/DTO与scheduling适配器，拆分inventory底层writer/receipt防回调，前移验证编译DAG与真实Bean无环；禁止Lazy/循环开关/空实现。
  - 文件与门禁补齐：新增C10列明的 `J/orders/port/`、`J/inventory/port/` 接口/本域DTO及@NamedInterface，新增 `J/scheduling/integration/orders/`、`integration/inventory/` 适配器，拆出inventory底层InventoryAllocationWriter；修改 `T/YumiApplicationTest.java` 验证完整应用真实Bean图。适配器不得回注OrderService/AfterSalesService/FulfillmentService/InventoryService，代理MANDATORY入口必须从外部事务调用；单独ModuleStructureTest不等于Spring启动通过。
  - 测试：`B(ModuleStructureTest,YumiApplicationTest,SchedulingTaskApiTest,SchedulingTimeCalculatorTest,OrderSchedulingSnapshotTest,ReportApiTest)`；原多订单建任务、快照冻结、报表非零结果继续成立，编号唯一且ST前缀。基线阶段只记录迁移、bean加载、`ModuleStructureTest`和不依赖新来源写入的计算/快照用例；多订单创建和编号待3.1/3.4，非零报表待4/5/6组事实与9.1接线后完整重跑，不能把启动成功称上述整组测试通过。
  - 接口：产生后续任务使用的 `SchedulingTaskService.create(CreateRequest)`、`SchedulingVerificationService.verify(long,VerifyRequest)`、`SchedulingFlowService`、`AfterSalesSchedulingService`；方法行为在后续任务逐项替换，不在此声称数量模型已完成。
  - 验收分层：包/SQL/公开调用及启动证据是后续任务的启动前置；删除旧来源表后的新写模型须由3.1/3.4/5.3提供真实实现，不恢复旧表作兼容。完整验收在9.1报表回归补齐后关闭，不阻止前述生产者开始施工。
  - 证据契约：实际迁移文件清单、跨模块调用点、旧名分类、已运行方法及后置方法清单、最终测试输出和编号实值；命名任务RED为新路径/编号契约失败，不用包编译错误充数。
  - 人工证据：不适用（后端迁移）；正式页面验收由8组承担。

- [ ] 2.2 固定新 API 契约并删除旧生产及手工返工创建映射
  - 依据/依赖：S4/S10/S23/S25、O5；依赖2.1、C10（orders/inventory自有端口、DTO及真实适配器接线）。文件：修改 `J/scheduling/task/SchedulingTaskController.java`、`verification/SchedulingVerificationController.java`、`source/ReworkSourceController.java`、`scrap/SchedulingScrapController.java`、`reminder/SchedulingReminderController.java`、`aftersales/AfterSalesSchedulingController.java`；新增 `T/SchedulingApiContractTest.java`。
  - 做法：①按C4逐条改@RequestMapping并用完整Spring上下文枚举映射断言；②保留返工GET，删POST建源及旧来源专属建任务转发入口，统一C2创建；③提醒GET从旧`/production-reminders/incomplete`明确改为`/scheduling-reminders`；④售后创建固定`/scheduling-sources/tasks`，消除前端旧`/plans`误配；⑤修改 `J/orders/fulfillment/FulfillmentService.java` / FulfillmentViews，在既有GET /api/orders/{id}/fulfillment的每条明细增加schedulingSources，通过C10的orders自有OrderSchedulingSourceQueryPort取真实ID及本域DTO，再由HTTP读该ID创建一次，不能直接import排班服务/SourceView；⑥结构映射验收与`ModuleStructureTest`在2.1前移执行，不能等2.2末尾才发现模块环。
  - 测试：`B(ModuleStructureTest,SchedulingApiContractTest)`；`exposesCanonicalSchedulingRoutes`、`rejectsRetiredEndpoints`、`sourceSelectionCanCreateTask`、`requiresAuthentication`；旧路径已登录返回NOT_FOUND，无匿名探测把AUTH_REQUIRED误判为仍有路由。
  - 接口：C4对应GET/POST，无旧别名和兼容重定向；保留统一信封。新增写守卫由各业务任务实现，此项先固定方法/路径/字段骨架和读写连通性。
  - 证据契约：实际handler清单、旧路径HTTP结果、读来源→建任务的请求响应与ID；不可只mock Controller。
  - 人工证据：不适用（HTTP黑盒）；前端新API调用取证在8.1。

- [ ] 2.3 删除超额实现、预占资源和专属错误码
  - 依据/依赖：S4/S25；依赖2.1。文件：删除原 `J/production/overtime/` 迁移范围中的实现，不建立新overtime包；修改任务/来源/提醒Repository、`J/shared/error/ErrorCode.java`；替换 `T/OvertimePreemptionApiTest.java`、`T/OvertimePreemptionConcurrencyTest.java` 为新增 `T/OvertimeRemovalTest.java` 的退役断言。
  - 做法：①先检索所有调用，分清未来预占释放与普通来源释放；②删超额Controller/Service/Repository/Views、预占/未来调整分支及无调用错误码；③保留INCOMPLETE提醒、NORMAL/REWORK和其他排班；④扫描报告/铺数脚本/API类型，后续9.1回收运行入口，历史归档不改。
  - 测试：`B(OvertimeRemovalTest,SchedulingIncompleteAndCancelTest,OtherScheduleTest)`；OVERTIME/REMAKE创建返回VALIDATION_INVALID，旧端点NOT_FOUND，未来日期计划量无变化，普通未完成提醒仍存在。
  - 关键实现：任务类型判断为 `Set.of("NORMAL", "REWORK").contains(taskType)`；不能把OVERTIME重命名为新来源类型或保留隐藏预占表。
  - 证据契约：删除/保留清单、全仓命中分类、四个拒绝/保留断言实值、RED/GREEN；禁止以搜索零命中误删历史文档。
  - 人工证据：不适用（删除服务）；页面无超额入口需8.1–8.4另取证。

- [ ] 2.4 保留其他排班工时闭环并隔离商品数量
  - 依据/依赖：S1/S22；依赖2.1。文件：迁包后的 `J/scheduling/otherschedule/OtherScheduleService.java`、Controller/Views/Repository；修改 `T/OtherScheduleTest.java`。
  - 做法：①保留C4其他排班全部现有路径，不因统一工作台而并入商品任务表；②创建验证在职员工、小时非负、分钟0–59、合计正数；③一次核验后更正追加原值/新值/原因，拒绝覆盖原记录；④列表返回日期/员工/分钟/状态以便前端合并日历。
  - 算法/测试：`totalMinutes = hours * 60 + minutes`；`B(OtherScheduleTest)` 中 `recordsNinetyMinutesWithoutQuantityFacts` 验90，`correctionKeepsOriginalVerification` 验90→105且原90保留，`rejectsSixtyMinuteRemainder` 拒分钟60；前后商品、库存、履约及制作工作量均不变。
  - 事务/错误：H，其他排班行锁；核验/取消/更正遵守现有一次性与状态边界，VALIDATION_INVALID/STATE_ALREADY_VERIFIED/STATE_NOT_CANCELABLE；不参与来源锁和正常容量。
  - 证据契约：实际分钟记录、更正前后行、无商品事实的作用域计数、API响应和B命令RED/GREEN。
  - 人工证据：本项不适用（后端）；同级周历位置与新建入口在8.2/8.3签字。

## 3. 来源额度、实物流入、容量与任务读写

- [ ] 3.1 建立统一来源查询和初始正常额度计算
  - 依据/依赖：S5/S7、S12、O5；依赖1.4/1.5/2.1。普通无实物创建先交付；实物流变更依赖4.2/4.7及6.5/6.7通用能力后回补。文件：新增 `J/scheduling/source/SchedulingSourceService.java`、`internal/SchedulingSourceRepository.java`、`internal/SchedulingSourceRow.java`；修改 `J/scheduling/internal/OrderSchedulingReference.java`、`J/orders/order/OrderService.java`、`J/orders/change/OrderChangeService.java`及请求/视图、订单确认时的来源接入；新增 `T/SchedulingQuantityTest.java`、`T/OrderSchedulingLifecycleTest.java`。
  - 做法：①订单确认、ADD及普通订单变更按冻结路线生成可追溯初始授权；普通无实物的确认/ADD、10→12→8及等总量路线变更先交付，增加只产生新起因来源，减少先处理未发有效额度并要求显式处置/取消集合；已有库存接入跳过的工序扣对应需加工额；②返回C2 SourceView，聚合PENDING和已核验处理，不按所有非取消计划重复扣；③总量与终止/处理分别保存，负余额当不变量错误，不`max(0)`掩盖超占；④所有来源按owner+目标+路线隔离，售后不回普通需求；⑤普通变更不复用售后case或demandAdjustmentId，按routeSnapshotId比较有缝/无缝及工艺版本，取消/关闭守卫重验owner、排班/执行/库存/发货/收款事实，独立售后与合法INVENTORY链不误禁；⑥保留普通确认/ADD、增减、取消、普通生命周期的同一事务与幂等回滚边界，新增`OrderSchedulingLifecycleTest`覆盖普通变更与工艺snapshot。
  - 全单变更接线：按C10单一Disposition端口一次收集/锁定全部ADD、增减及实物处置，不逐行L，不另调Lifecycle.applyOrderChange。确认ADD同事务落order_change_item_applications真实映射，双同商品ADD按origin行区分。`B(OrderSchedulingLifecycleTest)`新增 `mixedOrderChangeUsesOneLockPlan`、`twoSameProductAddsKeepDistinctOriginMappings`；无实物核心先GREEN，混合转库待6.7回补且任务不提前勾选。
  - 核心算法：`processed = sum(qualified + rework + scrap)`；`balance = totalQuantity - terminatedQuantity - pendingPlanned - processed - invalidatedUnoccupiedQuantity`，失效分支按C7防止与PENDING双扣，真实失效生产者6.6就绪后回补。来源服务提供 `SourceView get(long sourceId)`、`List<SourceView> listForOrderItem(long orderItemId)`，SQL锁定调用只在L事务内使用。
  - 份额生产核心：本项新增 `J/scheduling/source/SchedulingPurposeAllocationService.java` 及内部Repository，初始建源同事务持久化physicalShareId及单用途/单路线allocationId、跨段映射；其它来源生产者复用公开核心，6.5再扩展替代。新增 `initialSourcesHavePersistentPurposeRouteShares`，混合初始10/4有真实4/6份额，不能到6.5才补首次用途记录。
  - 测试：`B(SchedulingQuantityTest)`；`initialRequirementsAreTenTenFour`=10/10/4；`verifiedSixReturnsFourWithoutDoubleCounting`=4；`inventorySkipDoesNotCreateExtraDemand`；`isolatesOwnersAndTargets` 用两个非零owner证明不串量。
  - 接口/错误：C4订单可排读接口、SourceView；SOURCE_INVALID/SOURCE_INSUFFICIENT，创建校验在3.4。前端消费8.3/8.4。
  - 证据契约：来源原额/终止/PENDING/处理/余额五列实值、订单需求仍10、测试方法RED/GREEN与接口读取结果。
  - 人工证据：不适用（数量算法）；来源呈现由8组检查。

- [ ] 3.2 将下游可执行改为真实兼容流入的稳定分配
  - 依据/依赖：S8/S12、O1；依赖3.1。普通领用/取消与原发货路线是本项核心生产者，必须在5.1前交付，不等待5.9；逆向保护在3.2后补齐。文件：修改 `J/scheduling/flow/SchedulingFlowService.java` 的 `inflowFor`、`J/scheduling/verification/SchedulingVerificationService.java` 的 `executableFor`、`J/inventory/InventoryService.java` 普通领用/取消与 `J/orders/ledger/FulfillmentLedger.java` 原发货接入；新增 `T/SchedulingExecutableQuantityTest.java`、`T/ShipmentRouteLineageTest.java`。
  - 做法：①移除“REWORK/售后直接等于计划量”的通用分支，首道授权/返工来源/下游流入按来源形态分别求可执行；②下游只读合格流转与兼容库存事实，扣已消费，不读上游计划当流入；③同来源可执行按任务日期、明细ID稳定分配并在L内重算；④返工不得跨目标借余额，报废授权不是发生工序流入；⑤普通领用真实写阶段流入/SHIPPABLE及路线份额，普通领用取消只在全部接入份额未真实加工、未被核验/下游/发货/转库等消费且计划已显式合法解除时一次反向恢复，计划未核验或已取消不能豁免这些边界，不依赖执行字段或人工确认，失败不得恢复库存；⑥普通发货草稿/确认按必填seamQuantity、真实库存领用或终点核验份额和routeSnapshotId保存`shipment_source_links`，不从原始IN流水重分摊；所有未核验计划统一按C9同兼容池taskDate/itemId分配，无现场加工保护，读与核验锁内同算法。
  - 核心算法：`executable = min(itemRemainingPlan, assignedCompatiblePhysicalBalance)`；首道MAKING的无实物正常授权单独以合法授权余额执行，不套“下游必须已有实物”限制。
  - 真实生产及逆向：按C5扩OpeningRequest/InventoryPage的期初路线工艺证据，普通及售后复用扩展领用头/行；成品接入同事务生成不可排SHIPPABLE根，GET返回sourceId/allocationId/terminalAvailableQuantity及证据。新增 `finishedInventoryRootIsQueryableButNotSchedulable`。按C10先协调L再writer扣库；cancel与reverse同起因串行去重；核验/处置入库通用孤立冲销拒绝、期初合法冲销保留。普通发货路由全量覆盖创建/草稿改量/确认/作废/等量更正，`B(ShipmentRouteLineageTest)`验证同份额消费与反向，无消费第二次；后置加工终点与减量竞争待4/6组回补。
  - 普通库存适配：修改 `J/inventory/InventoryService.java` 普通领用与 `J/orders/ledger/FulfillmentLedger.java` 接入，将兼容批次按实际完成阶段写入对应普通流入/SHIPPABLE事实，同步3.1跳过工序的额度，不伪造制作核验或重复扣库。用1.5的createConfirmedOriginalShipment走订单确认→期初成品入库→普通领用→普通发货确认，新增 `openingFinishedStockCanBackOriginalShipment`；在5.1之前交付此真实夹具，不依赖4.5加工终点或5.6售后领用。
  - 测试：`B(SchedulingExecutableQuantityTest)`；`waitingPlanHasZeroExecutable` 计划10流入0→0；`sixActualInflowAllowsOnlySix`→6；`stablePriorityDoesNotAllocateSameInflowTwice` 两计划争6合计≤6；`scrapDoesNotFeedItsOriginalNode`。
  - 接口/错误：详情返回实际流入和可执行，verify超可执行返回QUANTITY_NOT_EXECUTABLE，取消不以可执行量为前提；C4无额外路径。前端不计算本值。
  - 证据契约：有计划无实物/有6实物的HTTP与SQL差异、排序输入输出、超执行回滚、B命令RED/GREEN。
  - 人工证据：不适用（稳定分配算法）；等待上游文案在8.2验证。

- [ ] 3.3 使用实体容量锁覆盖全部 NORMAL 的数量硬约束
  - 依据/依赖：S6/S18、O4；依赖1.4/3.1。文件：修改 `J/scheduling/capacity/SchedulingCapacityService.java`、`J/scheduling/task/internal/SchedulingTaskRepository.java` 的 normalCapacityUsage/lockNormalCapacityUsage；修改 `T/ProductCapacityTest.java`，新增 `T/SchedulingCapacityConcurrencyTest.java`。
  - 做法：①按L在所有者/来源之后insert-if-absent容量键，再锁定该键；②按产品+日期+工序聚合所有未取消NORMAL原计划，含售后、重过、补做，不再按sourceType豁免；③VERIFIED仍算原计划，CANCELLED才释放，REWORK不算；④批量先按容量键汇总本次各明细再校验，避免同行各自通过。
  - 算法：`used + requested <= moldQuantity * dailyBatchLimit`；资源remaining只用于展示，真实超额判断不得依赖截零结果。
  - 测试：`B(ProductCapacityTest,SchedulingCapacityConcurrencyTest)`；`afterSalesNormalSharesCapacity` 满额后售后NORMAL拒绝；`verifiedPlanKeepsTenCapacity`；`emptyDayConcurrentCreatesCannotOverbook` 空日容量5两请求3只有一个成功；`reworkDoesNotConsumeNormalCapacity`。
  - 接口/错误：C2创建，CAPACITY_EXCEEDED；整批任务/来源无残留。同产品不同工序不可互相挤占。
  - 证据契约：锁行SQL、两个真实线程屏障与结果、used/requested/max实值、历史10保留断言、RED/GREEN。
  - 人工证据：不适用（锁与资源验证）；不把制作工时超出当本项容量超额。

- [ ] 3.4 重写任务创建为整批来源驱动的原子命令
  - 依据/依赖：S2/S5/S6/S15/S24；依赖3.1–3.3。取消/核验守卫遵循1.1核对的2026-09-27已明确规则及C9，不设开始操作；独立售后来源不可因owner校验误禁。文件：修改 `J/scheduling/task/SchedulingTaskService.java` 的 CreateRequest/ItemRequest/create/planItems/applySourceOccupation、Views和Repository；修改 `T/SchedulingTaskApiTest.java`。
  - 做法：①接C2，只从sourceId读取owner/产品/目标/用途，匹配员工工种、日期与taskType；②先收集全部owner/来源/容量键，按L锁定后重读；③拒绝同一请求有效重复源行，按真实来源保存快照/分配；④一次创建头及全部明细，只占用来源/资源，不产生合格或覆盖新增；⑤返回真实来源及派生量，不留null占位；⑥创建重验owner与来源归属，普通入口拒绝售后来源但售后专用入口不误禁独立case；真实流入按C9同兼容池taskDate/itemId分配，不生成或依赖开始字段。
  - 核心实现顺序：`validateShape -> collectLockKeys -> lockAndReload -> validateAll -> insertTask -> insertItemsAndAllocations -> appendFacts -> getTask`；公共创建核心同时供5.5专用售后调用，售后授权不能在这里随手raiseTotal。
  - 测试：`B(SchedulingTaskApiTest)`；`createsOneHeadForThreeProductsAcrossOrders` 1头3行；`duplicateSourceRowRollsBack` CONFLICT_DUPLICATE；`oneInsufficientSourceRollsBackAll` SOURCE_INSUFFICIENT且头/行/占用零增；`ineligibleEmployeeCreatesNothing` EMPLOYEE_NOT_ELIGIBLE；资料改动后冻结分钟仍原值。
  - 接口：C4 POST任务 → TaskView，H；普通入口售后sourceId返回SOURCE_INVALID，不能仅隐藏前端来源。
  - 证据契约：四类失败请求、成功三行快照/分配行、无合格事实与无覆盖新增断言、同键重放仅一头、RED/GREEN。
  - 人工证据：不适用（后端创建）；全页新建在8.3验收。

- [ ] 3.5 实现当前工序返工矩阵及适用路线校验
  - 依据/依赖：S9/S13、O3/O4；依赖2.1。文件：修改 `J/scheduling/SchedulingNodes.java`，新增 `T/NodeFormMatrixTest.java`。
  - 做法：①提供 `boolean canRework(String currentNode,String targetNode)` 与独立 `boolean canReturnRework(String targetNode,boolean seamRequired)`；②制作仅制作、装袋仅制作、缝边仅制作/装袋；③售后退回入口三道适用目标，但再次修补用currentNode函数；④删旧“同工序返工”注释和顺序下标通用比较，不复用库存接入矩阵。
  - 关键代码：`switch (currentNode) { case MAKING, PACKING_BAG -> MAKING.equals(targetNode); case SEAM_CUTTING -> MAKING.equals(targetNode) || PACKING_BAG.equals(targetNode); default -> false; }`。
  - 测试：`B(NodeFormMatrixTest)` 参数化3×3全矩阵；另测有缝/无缝售后入口各3目标和非法node；缝边REWORK报废规则由4.3一并守卫。
  - 接口/错误：服务调用返回字段级VALIDATION_INVALID，字段路径指向具体reworkAllocations下标；前端合法选项镜像但后端权威。
  - 证据契约：矩阵9项与售后6项实际结果、非法字段定位、RED/GREEN；无数据库/幂等适用（纯规则函数）。
  - 人工证据：不适用（矩阵自动断言）；名称必须使用“缝边剪袋”。

- [ ] 3.6 验证来源并发拆分与创建幂等的事务边界
  - 依据/依赖：S10/S15/S24/S25；依赖3.4。文件：修改改名后的 `T/SchedulingConcurrencyTest.java`、`T/SchedulingIdempotencyTest.java`，修改来源Repository的锁内检查。
  - 做法：①真实来源5先分2+3再请求1；②两连接同步竞争5各3，先锁owner再source，不能只串行mock；③同键同请求/同键不同数量各重放；④注入第三明细失败，检查业务事实而非仅响应；⑤请求伪造balance/executable/status不得改变权威数据。
  - 测试：`B(SchedulingConcurrencyTest,SchedulingIdempotencyTest)`；`splitSourceAcrossTasks`、`concurrentThreePlusThreeConsumesOnlyThree`、`replayReturnsOriginalTask`、`differentFingerprintConflicts`、`clientDerivedFieldsCannotOverrideServer`。
  - 接口/错误：POST任务，SOURCE_INSUFFICIENT/CONFLICT_IDEMPOTENCY/VALIDATION_INVALID；H/L。响应正确还须验证失败请求未遗留任务、分配、资源计入。
  - 证据契约：两个幂等键/请求指纹、真实线程时间与结果、来源5/占用3/余额2、一次成功一失败、RED/GREEN及事实唯一键查询。
  - 人工证据：不适用（并发及HTTP行为）；不能用全局COUNT受其他用例污染。

- [ ] 3.7 分离未完成提醒、来源余额与历史资源
  - 依据/依赖：S18/S19；依赖3.1、4.3（运行验证前）。文件：修改 `J/scheduling/reminder/SchedulingReminderService.java`、internal Repository/Row，以及任务释放来源代码；修改 `T/SchedulingIncompleteAndCancelTest.java`。
  - 做法：①核验时未完成量按同source/target/purpose/route退出PENDING占用，已完成计processed；②原行保持VERIFIED、原计划及分钟不变，只有仍有效待重排的未完成追加INCOMPLETE提示；③延期只改提醒处理事实，不改任务日期/数量、不新建计划；④有效未完成再安排引用原来源创建新任务，不能重验旧行；⑤C7上游报废造成的失效未完成保留数量/处置关联，不回可排、不再提示重排，待6.6真实失效链后补 `invalidatedIncompleteIsAlreadyDisposed`。
  - 算法：计划10完成6后 `pending=0, processed=6, balance=4, oldDateCapacity=10`；返工5完成3只回该目标2，不增加普通需求或售后覆盖。
  - 测试：`B(SchedulingIncompleteAndCancelTest)`；`tenCompletedSixRequiresNewPlanForFour`、`reworkIncompleteReturnsSameTargetOnly`、`deferDoesNotRescheduleOrChangeQuantity`、`verifiedItemCannotCancelOrVerifyAgain`。
  - 接口：GET新提醒路径/POST defer、POST新任务；STATE_ALREADY_VERIFIED/STATE_NOT_CANCELABLE，H/L。
  - 证据契约：旧10/核验6/新4三组行与日期容量、返工/售后非零隔离、提醒延期审计、RED/GREEN。
  - 人工证据：不适用（后端）；提醒与已核验状态同时出现由8.2检查。

- [ ] 3.8 读模型返回真实来源与稳定事实时间线
  - 依据/依赖：S2/S3/S19/S23；依赖3.4，4/5/6组事实增加后持续补齐。文件：修改 `J/scheduling/task/SchedulingTaskViews.java`、Service.get/list/facts、`internal/SchedulingFactRepository.java`；修改 `T/SchedulingTaskFactsTest.java`。
  - 做法：①头状态仅从有效明细/日期派生，不建状态写接口；②详情返回SourceView、核验/取消事实、用途份额、路线、轮次、未完成、报废/补做引用；③事实排序固定`factTime,factType,factId`，展示键`factType:factId`；④关联普通与售后来源时按owner分支，避免售后结果恒null/0；⑤来源/核验/库存可反向定位真实单据。
  - 测试：`B(SchedulingTaskFactsTest)`；`mixedPendingVerifiedIsPartial`、`allCancelledIsCancelled`、`verifiedWithIncompleteKeepsReminder`、`sameIdAcrossFactTypesHasStableOrder`、`afterSalesLineageIsNotNull`。
  - 接口：C4三个任务GET，只读；无前端计算权威头状态；详情不返回可编辑假模型。
  - 证据契约：有普通/售后/返工/库存的非零响应、同ID不同类型排序、时间精度与字段null审计、B命令RED/GREEN。
  - 人工证据：本项不适用（读模型）；8.5检查正式只读详情与追溯跳转。

## 4. 取消、核验与正常/返工/补做流转

后续任务继续遵守C1–C4及C9已明确边界；没有开始命令、登记字段或核验开始前置。

- [ ] 4.1 交付未核验取消资格与无开始操作的跨端契约
  - 依据/依赖：2026-09-27用户决定、S16、O5、C9；依赖1.1文档契约核对、1.5、3.2、3.4。文件：修改 `J/scheduling/task/SchedulingTaskService.java`、`internal/SchedulingTaskRepository.java`、`SchedulingTaskItemRow.java`、Views；扩展 `T/SchedulingApiContractTest.java`、`T/SchedulingTaskItemCancelConcurrencyTest.java`。
  - 做法：①提供取消资格校验核心，由4.2实际取消及普通/售后显式处置复用；按L锁内核对task/item/source owner、case归属、PENDING及无核验，不检查日期或现场加工；②未来、当天、过期均按同一资格，0可执行也可取消；③已核验拒绝，已取消仅同键重放首次结果，不新增写事实；④API/View/数据库均不引入开始登记字段、方法或端点，前端无开始按钮或额外人工确认开工步骤；⑤取消资格不作为无实物终止、领用逆向或直接报废资格，后者继续按真实事实守卫。
  - 测试：`B(SchedulingApiContractTest,SchedulingTaskItemCancelConcurrencyTest)`；`pendingCancellationEligibilityIgnoresTaskDate`参数化未来/当天/过期；`physicalProcessingDoesNotBlockPendingPlanCancellation`；`verifiedItemIsNotCancelable`；`noStartEndpointOrExecutionFields`验证handler、响应/请求契约及迁移结构，无隐形替代字段。未来和当天只验取消，核验日期限制保持。
  - 接口：沿用C4 cancel `{reason}` → TaskView，无新增HTTP；STATE_NOT_CANCELABLE，H/L。既有管理员认证及普通/售后归属权限保持。
  - 证据契约：三日期及现场加工/核验状态的资格矩阵、API/DDL字段与映射检查、拒绝/重放响应、RED/GREEN；取消成功不证明现场未加工，不能伪造系统可感知未上报现场。
  - 人工证据：不适用（后端契约）；业务决定已明确，实施未完成，8.2另验取消意图及无开始入口。

- [ ] 4.2 原子取消未核验计划并仅释放本计划有效占用与资源
  - 依据/依赖：S15/S16/S18、O5、C9；依赖4.1资格核心；取消/核验竞争完整验收待4.3回补，不反向阻塞取消核心。文件：修改 `J/scheduling/task/SchedulingTaskService.java` 的 cancelItem/releaseOccupation 及Repository取消SQL；修改 `T/SchedulingTaskItemCancelConcurrencyTest.java`。
  - 做法：①取消原因必填，按L取锁后检查PENDING及无核验事实；②未来、当天、过期和现场已加工未核验均可取消整条原计划，不按现场完成数静默拆改；③追加审计、释放本明细仍有效source占用与NORMAL资源，不改上下游其他明细、真实流入/加工/消费事实或需求/覆盖，不复活失效份额；④SQL条件更新及唯一核验约束防并发穿透；⑤与4.3核验用相同锁序，先成功者生效、后者按终态拒绝，任一写入失败全回滚。
  - 测试：`B(SchedulingTaskItemCancelConcurrencyTest,SchedulingIncompleteAndCancelTest)`；`futureSameDayAndExpiredPendingCanCancel`、`processedButUnverifiedPlanCanCancel`、`verifiedItemCannotCancel`、`cancelReworkReturnsOnlyItsSource`、`cancelDoesNotCascadeOrDeleteInflow`、`cancelAndVerifyOnlyOneWins`；6.6回补失效份额取消不复活，6.8回补取消不放宽库存/实物处置守卫。
  - 接口：C4 cancel `{reason}` → TaskView；STATE_NOT_CANCELABLE，H/L。计划10现场做6但未核验可整条取消，不能删除真实实物或将取消当作完成6的核验，也不自动创建剩余4任务。
  - 证据契约：取消前后同源/其他源/容量/下游/真实流入与覆盖、两种竞争先后结果、原因审计与同键重放；测试RED/GREEN，历史实物边界不因取消失效。
  - 人工证据：不适用（服务守卫）；8.2展示已核验禁用原因，直接HTTP也须拒绝已核验取消。

- [ ] 4.3 重写批量核验的日期、一次性、等式和原子事务骨架
  - 依据/依赖：S13/S16/S17/S18；依赖3.2、3.5、4.1。文件：修改 `J/scheduling/verification/SchedulingVerificationService.java` 的 VerifyRequest/ItemRequest/verify/requireInputs/verifyOne、Repository、Views；修改 `T/SchedulingVerificationApiTest.java`。
  - 做法：①接C2，一次读全部明细引用后按L先owner再task，替换旧verify先锁task的顺序；②按Clock要求businessToday>taskDate，允许完成0，正完成无开始标记前置，按C9与可执行读同算法在锁内重算分配；③校验不重复、归属、PENDING/无核验、非负、完成不超plan/executable、返工目标合计、缝边scrap=0；④全部验证通过才落核验并调用4.4–4.7结果处理；⑤每行VERIFIED，未完成派生，不再修改原计划；中途异常整批回滚。
  - 算法：`completed = qualified + rework + scrap; incomplete = planned - completed`；`sum(reworkAllocations.quantity) == rework`；`SEAM_CUTTING -> scrap == 0`。请求不能提供权威completed/incomplete/status。按C2规范化唯一份额，多个用途/路线份额必须完整引用allocationId并逐组守恒；新增 `samePurposeDifferentRoutesRequireGroups` 与 `groupResultsPreserveInitialRouteShares`，以初始有缝4/无缝6验证不能由总合格量猜路线。4.4–4.7接通后验证两类真实结果，6.6只扩展用途替代/失效，不首次引入初始路线分组。
  - 测试：`B(SchedulingVerificationApiTest)`；`sameBusinessDayRejectsVerification`、`shanghaiNextDayAllowsVerificationBeforeUtcMidnight`、`positiveCompletionNeedsNoStartFlag`、`zeroCompletionReturnsValidBalance`、`cancelAndVerifyOnlyOneWins`、`secondItemFailureRollsBackWholeBatch`、`newKeyCannotVerifyAgain`、`seamNormalAndReworkRejectScrap`。使用1.5固定Clock：任务日10月5日，`2026-10-05T15:59:59Z`拒绝，`2026-10-05T16:00:00Z`本地次日允许；允许路径先用完成0请求（无开始前置）证明真实HTTP日期守卫。
  - 验收分层：核心阶段完成日期/未取消未核验/一次性/等式守卫及C9锁内分配、零完成核验和来源处理原子性，供4.4–4.7接入；正完成结果处理不能以空处理器视为成功，相关场景保持待集成。单用途完整验收须4.4–4.7、3.7及5.5售后任务入口就绪后重跑整类；6.6再扩展混合用途并重跑本类回归。本项未满足完整验收前不勾选，消费者只依赖明确的核心范围。
  - 接口：C4 verify → VerificationView；VALIDATION_INVALID/VERIFICATION_EQUATION_INVALID/QUANTITY_NOT_EXECUTABLE/STATE_ALREADY_VERIFIED，错误定位 `items[index].field`；H/L。
  - 证据契约：跨日时钟与字段错误、核心/完整分别对应的方法和命令、三行批量第二行失败的全部相关表零增、原计划10完成6余4、同键重放一次/新键拒绝、RED/GREEN。
  - 人工证据：不适用（核验后端）；批量一次请求及错误归行在8.6验收。

- [ ] 4.4 在核验内完整拆目标生成返工来源及多轮父子链
  - 依据/依赖：S9/S10、O3/O4/O5、O8；依赖3.1、3.5、4.3。文件：修改 `J/scheduling/source/ReworkSourceService.java`、Views和Repository；核验Service调用；普通/售后全局入口共用`caseId`归属解析；新增 `T/ReworkPoolTest.java`。
  - 做法：①移除旧“只增加reworkPending，随后手工建源”的链路；②按用途份额+目标规范化分配，保存核验分配行及REWORK来源，保留发生工序、owner、caseId、路线、root、parent、round；③新轮当前source被完成量消费，子源round+1，只未完成回原源；④GET返工列表/详情返回C2及原事实，不自动建任务；⑤普通订单、退回售后、全局返工入口均通过统一source/case公开服务取得售后归属，不能依赖页面导航参数，也不能把独立售后误归普通；⑥数据库唯一键兜底同分配不重复来源。
  - 核心算法：`for allocation in validatedAllocations: insertAllocation(); createReworkSource(originVerification, currentNode, targetNode, owner, purpose, route, parent, quantity)`；该循环与核验同事务，禁止独立提交。
  - 测试：`B(ReworkPoolTest)`；`fiveReworkSplitsMakingTwoPackingThree`；`fourAllocatedForFiveRollsBack`；`oneSourceCanCreateTwoAndThree`；`nextRoundOneDoesNotRestoreConsumedThree`；售后缝边再返工目标不能缝边。
  - 接口：verify及返工GET；SOURCE_INSUFFICIENT/VALIDATION_INVALID/VERIFICATION_EQUATION_INVALID；来源明细任务工序必须等于targetNode。
  - 证据契约：核验/分配/source/parent/root/round真实行、任务数不增、旧源0新源1、一源多任务和重放断言、RED/GREEN。
  - 人工证据：不适用（来源生成）；来源与目标在8.4/8.5呈现。

- [ ] 4.5 按冻结路线流转正常合格并分开订单/售后终点
  - 依据/依赖：S8/S12/S21、O4、I2；启动依赖3.2、4.3核验核心及5.2覆盖核心；先交付普通流转、售后中间流入/补发终点，与5.7同批接入库存终点。真实售后链需5.3/5.4建源及5.5创建；完整单用途验收待5.7入库核心，不等待其6组后置场景。首次合格承接5.3既有等待额度，不重复建源或覆盖。文件：修改 `J/scheduling/flow/SchedulingFlowService.java` 的 qualifiedFlows/registerQualified；修改 `J/orders/ledger/FulfillmentLedger.java` 与 `AfterSalesLedger.java` 的公开接入；新增 `T/SchedulingQualifiedFlowTest.java`。
  - 做法：①制作仅入同owner装袋；②装袋读取来源路线份额，适用缝边才入缝边，其余到终点，不再用全局`seamQuantity - historicalInflow`决定重过件路线；③缝边合格到终点；④普通终点写普通可发，售后终点调用5.2/5.7按用途处理，中间工序绝不直接registerInflow为可补发；⑤流入以核验+用途份额+目标唯一。
  - 测试：`B(SchedulingQualifiedFlowTest)`；`initialTenSplitsSeamFourAndShippableSix`；`makingQualifiedEightIsNotTerminalForEitherOwner`；`afterSalesFlowDoesNotTouchOriginalLedger`；`replayDoesNotDuplicateQualifiedFlow`。
  - 接口/事务：由verify内部调用，仍H/L单事务；不开放手工合格写入端点。下游授权与实物通过明确flow行关联，不把每道合格累加成客户完成。
  - 证据契约：三道流转SQL、原需求10与终点10、售后制作8时原台账不变且可补发0、失败回滚与RED/GREEN。
  - 人工证据：不适用（流转算法）；端到端从正式核验页验证在9.3。

- [ ] 4.6 将修补后续分为承接原等待额度与新增重过额度
  - 依据/依赖：S11/S12、O4/O6；依赖4.4/4.5。文件：修改 `J/scheduling/flow/SchedulingFlowService.java`、`source/SchedulingSourceService.java`、flow/source Repository；新增 `T/ReprocessingFlowTest.java`。
  - 做法：①从该实物链的已处理阶段和未消费等待授权判断，不用全订单累计总量猜；②下游未处理且已有授权时只追加实际流入，关联原waiting source；③下游已处理需重过时创建REPROCESSING正常来源及对应流入，客户需求不增；④继承有缝路线、owner、purpose及root；原实物工艺routeSnapshotId保持不变，重过份额继续继承原实物工艺snapshot，新正常制造使用其需求冻结快照，二者不能以相同seam标志互相替代；⑤后续NORMAL进入正常容量；⑥来源新建幂等键含上游核验份额+下游节点，防重过额度重复；普通始发流入与售后全局返工入口均沿统一caseId/来源链追溯。
  - 算例/测试：`B(ReprocessingFlowTest)`；`seamEightQualifiedTwoReworkFinishesAtTen`：制作修补1/装袋修补1→新增正常装袋1/缝边2→终点10；`makingRepairFeedsExistingPackingWaitOnly` 不增原装袋额度；`historicalSeamInflowCannotSkipReprocessing`；与4.7共同回归`repeatedMakingScrapFeedsOriginalPackingWaitOnce`，验证补做反复报废只承接原装袋等待一次。
  - 接口：verify/创建任务共用来源能力；REWORK只实际段，NORMAL后段CAPACITY_EXCEEDED照常拒绝，客户订购和缝边需求不改。
  - 证据契约：原等待/重过两种source与flow行、正常容量增量、终点非12、同链覆盖不增长的预留断言与RED/GREEN。
  - 人工证据：不适用（数量路由）；8.5追溯需区分“承接等待”与“重过”。

- [ ] 4.7 报废保留事实并按仍需交付生成唯一制作补做
  - 依据/依赖：S14、O7、I2；依赖4.4–4.6及5.2覆盖接口。文件：修改 `J/scheduling/scrap/internal/ScrapRecordRepository.java`、`SchedulingQuantityReturnRepository.java`、核验Service和ScrapController；新增 `T/ScrapReplenishmentTest.java`，调整既有 `T/SchedulingReworkScrapTest.java`。
  - 做法：①每次报废追加发生节点/owner/purpose/源/原因/核验引用；②普通仍需履约或售后补发用途先结束失败份额，再创建MAKING NORMAL的SCRAP_REPLENISHMENT来源及quantity_return；③库存用途只报废，退回直接报废不自动减少承诺、不自动任务；④返工报废计已处理，不恢复旧修补余额；⑤后续补做再次报废追加新链，绝不覆盖旧记录；⑥补做映射反复报废必须承接原等待一次：新补做不复活失效/报废份额，按`replacesPhysicalShareId`及原等待source/task引用只保留一次有效覆盖，并新增 `repeatedMakingScrapFeedsOriginalPackingWaitOnce`；⑦同一报废事实、补做起因和失效/替代映射均以幂等键防重复，失败整笔回滚。
  - 关键分支：`needsDelivery && purpose != INVENTORY -> createMakingAuthorizationOnce(scrapId, quantity)`；售后数量需5.2锁内检验对应路线新缺口，不能报废和显式授权各补一次。直接退回报废缺口由5.3显式授权，不假装加工核验。库存用途不补做时调用C7下游未处理份额失效核心（新增source内部InvalidationRepository），报废与失效同事务；6.6真实混合用途等待链后补完整回归，不把失效当取消资源或第二次报废。
  - 测试：`B(ScrapReplenishmentTest,SchedulingReworkScrapTest)`；`packingScrapTwoRequiresMakingBeforePacking`；`repairScrapDoesNotRestoreRepairPool`；`inventoryPurposeScrapHasNoReplenishment`；`newScrapPreservesOldVerification`。
  - 接口：verify、报废列表/详情/补做来源GET；缝边报废仍VALIDATION_INVALID；H/L。
  - 证据契约：scrap→MAKING来源唯一引用、原发生工序流入0、原需求/历史资源不变、库存用途无补做/库存/覆盖新增、RED/GREEN。
  - 人工证据：不适用（后端）；原工序和补做目标分列在8.5验收。

- [ ] 4.8 从多维非零事实重建来源、流入与资源投影
  - 依据/依赖：S7/S18/S24/S25；启动依赖3组已有来源/读写核心及4.1–4.7事实；完整验收还需5.1/5.3/5.5真实售后链、6.2–6.7终止/用途/库存事实，放在6组闭环后。3.8不必先完成所有时间线证据，双方使用已存在的真实事实增量验收。文件：新增 `T/SchedulingProjectionRebuildTest.java`；修改来源/flow/提醒Repository的事实汇总查询，不新建对外管理接口。
  - 做法：①夹具同时含两个普通owner、两个售后owner、三个工序、有缝/无缝、两轮返工、部分未完成、取消及报废；②独立由不可变事实计算各source总额/终止/占用/处理、流入/消费和日期正常资源；③与API投影逐键对账，不只比总和；④在测试事务内改变测试专属投影后调用重算，再验证恢复，不能改真实用户数据。
  - 测试：`B(SchedulingProjectionRebuildTest)`；`rebuildMatchesEveryOwnerSourceNodeAndDate`、`rebuildDoesNotDuplicateFacts`、`cancelAndIncompleteHaveDifferentResourceEffects`；重复重建两次结果一致、事实行数不变。
  - 接口/错误：GET读结果，重建只内部方法；负数/不一致必须抛可定位断言，不用零截断把检查变绿。
  - 证据契约：逐维非零输入/期望/实际对照、重建前后哈希或有序结果、重复重建无新事实、RED/GREEN；不能把全部0称守恒。
  - 人工证据：不适用（事实对账）；用户业务验收算例由9.3提供。

## 5. 售后受理、覆盖、授权、库存接入与补发隔离

- [ ] 5.1 将实际退回与补发需求从受理请求到视图分开
  - 依据/依赖：O1；依赖1.4/2.1。文件：修改 `J/orders/aftersales/AfterSalesService.java` 的create、Views及 `internal/AfterSalesRepository.java`；修改 `T/AfterSalesApiTest.java`、`T/AfterSalesConcurrencyTest.java`。
  - 做法：①保留有效已确认发货明细受理锁和累计受理上限；②退回和补发各自非负，不以二者相加限制acceptedQuantity，不强制相等；③默认退多少补多少仅在未提供补发值时应用，显式0不能被默认覆盖；④按C3接收必填`returnedSeamQuantity`与`replacementSeamQuantity`并分别冻结原发货实物路线/工艺快照和本次新制造路线/合法工艺快照，返回独立总量及有缝/无缝字段，禁止复制整单缝边量、按比例猜或以补发路线改写退回实物；⑤退款沿现有售后退款台账，不新增收款/应收逻辑；⑥退回路线生产于原发货有效消费链接，保存可用余额扣除其他有效退回占用后的真实路线份额及来源，供5.4按路线差额报废。
  - 测试：增加`returnedSeamQuantity`与原发货路线输入断言；补发路线与退回路线工艺快照分离，显式0、缺省、无快照正缝边拒绝分别取证。
  - 真实夹具前置：依赖1.5定义和3.2普通成品领用适配，先跑订单确认→期初成品库存→普通领用→普通发货确认→受理；不得用旧AfterSalesApiTest的直接INSERT发货/余额替代本项核心HTTP证据。新增 `acceptsFromRealInventoryBackedShipment`、`freezesExplicitReplacementSeamQuantity`、`rejectsMissingOrInvalidReplacementRoute`：原订购10其中缝边4，本次补发2明确缝边1，冻结1/1而非4；无合法工艺快照时正缝边量拒绝，显式0保留。
  - 测试：`B(AfterSalesApiTest,AfterSalesConcurrencyTest)`；`acceptsReturnFiveReplacementThree`、`acceptsReplacementWithoutReturnAndReturnWithoutReplacement`、`refundOnlyKeepsOriginalSettlement`、`invalidOrOccupiedShipmentCannotBeAccepted`。部分发货可受理，草稿/作废/超占拒绝。
  - 接口：C4订单售后GET/POST与case GET；AFTER_SALES_SOURCE_INVALID/AFTER_SALES_QUANTITY_EXCEEDED；H，受理先锁原shipment_item再售后owner。
  - 证据契约：四类合法售后载荷、原订购/已发/未交付/应收/状态和结清净额前后非零值、受理并发结果、RED/GREEN。
  - 人工证据：不适用（受理后端）；默认值与0区分在8.7验收。

- [ ] 5.2 建立按物理份额互斥迁移的售后覆盖台账
  - 依据/依赖：S21、O6/O7；依赖1.4/3.1。文件：新增 `J/orders/ledger/AfterSalesCoverageLedger.java`、`J/orders/aftersales/internal/AfterSalesCoverageRepository.java`；修改 AfterSalesViews/Repository；新增 `T/AfterSalesCoverageTest.java`。
  - 做法：①使用C1追加from/to bucket事件，source root与物理份额标识贯穿流转；②新增覆盖必须锁售后明细并重算gap，来源/计划/子轮/合格只能迁移同一份覆盖；③未排来源立即覆盖，创建/取消计划不增减覆盖，不设置start或执行登记事实；④终点迁到RESERVED，确认迁SHIPPED；报废结束失败覆盖再授权补做；⑤不同实物不能复用已满承诺，禁止负gap截零。
  - 算法：有缝/无缝分别 `gap = required - shipped - reserved - activeUnfinishedCoverage`，总数是两桶求和，有效覆盖是事件余额，不是 `sum(source.totalQuantity)`。公开入口 `coverage(long afterSalesItemId)` 返回C5总量及routes分桶；新增/迁移方法只接受业务起因、真实用途/路线份额引用和正数量，拒绝任意直接设余额或跨路线抵缺口。新增 `totalGapCannotCoverWrongRoute`，路线一gap0另一gap2时第一类新增1拒绝，整笔无事件残留。
  - 测试：`B(AfterSalesCoverageTest)`；`unarrangedFiveAlreadyCoversFive`、`threeAcrossMultipleStagesStillCoversThree`、`differentPhysicalGoodsCannotCoverSameDemand`、`terminalReservationAndShipmentAreTransfers`、`scrapReplenishesGapOnce`。
  - 接口：case/source读模型返回覆盖，创建/库存/退回调用同服务；SOURCE_INSUFFICIENT或对应售后数量错误沿错误矩阵，整笔失败无事件残留。
  - 证据契约：每bucket非零迁移前后、root/份额/起因唯一、来源和任务不重复加覆盖、RED/GREEN；orders拥有该账，scheduling不直接写其内部Repository。
  - 人工证据：不适用（覆盖守恒）；8.7/8.8显示覆盖与未覆盖不同数值。

- [ ] 5.3 拆出显式正常缺口授权及售后来源查询
  - 依据/依赖：O6/O8、S21；依赖3.1及5.2覆盖核心，真实受理夹具依赖5.1。文件：修改 `J/scheduling/aftersales/AfterSalesSchedulingController.java`、Service/Views和 `internal/AfterSalesSchedulingSourceRepository.java` 的迁移实现；新增 `T/AfterSalesNormalAuthorizationTest.java`。
  - 做法：①新增C4 POST scheduling-sources 接C3授权，不接受taskType/target/purpose任意覆盖；②锁owner后校验明细属于case、quantity>0、reason非空、quantity≤gap；③同事务创建MAKING NORMAL根授权及冻结路线所需的PACKING_BAG、适用SEAM_CUTTING等待授权，均为AFTER_SALES_NORMAL、共享root及被授权路线份额，parent按前后工序关联；这些是同一补发份额的各段加工额度，只登记一次覆盖，不建任务、不写实物流入；④POST仍返回MAKING根SourceView，GET只读列出各段真实sourceId，等待源可安排但executable=0，不能在GET或createTask时补建；⑤去掉旧createTask中的按需insert/raiseTotal建源，终止后新授权必须新根及新等待源ID，不复活旧链；⑥普通来源afterSalesItemId/afterSalesCaseId均null，售后两者非空且匹配并由服务端真实关联返回；全局返工按响应caseId进入专用创建，不靠前端导航猜归属，不混普通/售后或跨case。
  - 等待契约：这是O8“制作及后续等待计划”场景的生产者，不增加独立补发需求或新的授权入口；C3显式quantity/seamQuantity按5.1冻结的补发路线及5.2两类gap校验，本次有缝seamQuantity、无缝quantity-seamQuantity分别持久化初始用途/路线份额及allocationId，无缝不产生缝边额度。4.5首次合格只向对应等待源追加兼容流入，不重复增加授权；4.6仅在已处理阶段确需重过时创建新授权。1.2/1.4按根+授权起因+目标建立防重约束，允许多次显式授权各有独立根。后续用途变更按C7更新同份额等待映射，不覆盖整源用途。
  - 混合路线回归：新增 `authorizesExplicitRoutesAcrossMultipleRoots`、`rejectsCrossRouteGapBorrowing`、`reauthorizesOnlyTerminatedRoute`。需求5其中有缝2：先授权3其中有缝1，生成制作3/装袋3/缝边1且覆盖有缝1无缝2；再授权2其中有缝1，累计覆盖2/3；只有有缝gap1时申请无缝1即使总gap1也拒绝。终止哪一路线就只释放该路线覆盖及对应段，增加需求后用同路线新根重新授权。
  - 测试：`B(AfterSalesNormalAuthorizationTest)`；`authorizesOnlyUncoveredNormalMaking`、`createsWaitingSourcesWithoutFlowOrExtraCoverage`、`terminatedTwoIsNotRevivedByNewDemand`、`replayKeepsOneAuthorizationChainAndNoTask`、`rejectsManualReworkOrForeignCaseSource`。无缝授权5→制作5/装袋5/缝边0，覆盖仅5、流入0、任务0；有缝份额只增加对应缝边等待额度。先5终止2再需求增2，仅新建2、旧链terminated仍2。
  - 验收分层：授权/查询/同根等待/幂等核心先供5.5、6.2/6.3消费；终止后再次授权场景待6.2回补，再完成本项全部验收。禁止SQL手造等待源代替POST授权→GET选源链路。
  - 接口：GET/POST售后scheduling-sources；SOURCE_INVALID/VALIDATION_INVALID/售后数量超额错误；H/L。返回SourceView，不继续旧planNo/planType响应。
  - 证据契约：新旧根/等待sourceId、parent/root/目标/路线、原额/终止/覆盖与任务数、等待流入0、原因、重放、超gap及跨case失败无写入、RED/GREEN。
  - 人工证据：不适用（授权端点）；8.7显式按钮，不自动在增需求后调用。

- [ ] 5.4 一次退回核验按用途与目标分配并原子建源
  - 依据/依赖：O1/O3/O4、I2；依赖3.5/4.4/5.2。文件：修改 `J/orders/aftersales/AfterSalesService.java` 的verifyReturn、Views/Repository；经orders自有 `OrderSchedulingLifecyclePort.applyReturnVerification` 调用scheduling适配器，端口及DTO遵守C10；新增 `T/AfterSalesReturnAllocationTest.java`。
  - 做法：①接C3退回载荷，校验该明细未核验且实际退回与受理事实相容；②按purpose+target+routeSeamRequired合并校验，修补量+直接报废=returned，申报路线符合原实物适用路线、目标适用、补发份额≤该路线gap，不能用补发新路线改原实物；③按原发货路线对每一路线计算`returnedSeamQuantity/returnedNoSeamQuantity - repairAllocation`的报废差额，所有差额之和必须等于`scrapQuantity`，记录真实原消费份额和工艺快照；④同事务保存return verification/allocations/REWORK sources/scrap，复用3.1持久化初始用途及路线份额；退回入口用独立origin类型，不伪造MAKING发生工序；⑤直接报废不自动免承诺，未选库存用途的退回不入库；⑥拒绝新键二次核验，同键重放原响应；⑦原退回实物snapshot与新制造补发snapshot只做兼容校验，不互相覆盖。
  - 实退差异：核验显式接收returnedQuantity/returnedSeamQuantity，满足 `0 <= returnedSeamQuantity <= returnedQuantity <= acceptedQuantity`；允许不同于受理预录，锁定原发货及售后归属后逐路线重验其他明细占用，记录预录→实际差异并替代本明细未核验占用历史，不修改独立补发承诺。新增 `actualReturnReplacesProvisionalRouteOccupancy`、`actualReturnCannotConsumeAnotherCaseRouteShare`，失败不留差异/核验/来源；不依赖既有correct先修改未核验事实。
  - 算例：实退5 = 补发制作修补2 + 补发装袋修补1 + 库存缝边修补1 + 直接报废1；只有3新增补发覆盖、任务0、库存0。
  - 测试：`B(AfterSalesReturnAllocationTest)`；`allocatesFiveAcrossPurposeAndTargetAtomically`、`invalidRouteOrUnbalancedReturnRollsBack`、`repeatedReturnVerificationDoesNotCreateMoreSources`、`overCoverageRollsBackWholeReturn`。
  - 接口：C4 verify-return → CaseView；AFTER_SALES_EQUATION_INVALID/VALIDATION_INVALID/STATE_ALREADY_VERIFIED；H/L，不能从orders直调scheduling内部Repository。
  - 证据契约：分配4组实值、三源/一报废/零任务/零库存、非法第二组全回滚、原台账不变和RED/GREEN。
  - 人工证据：不适用（退回事务）；8.7核验页面逐用途/目标录入实测。

- [ ] 5.5 售后排班仅消费既有来源并复用统一创建核心
  - 依据/依赖：S6/S21、O4/O5/O8；依赖3.4、5.3授权核心、5.4退回建源核心；创建能力前置于5.7真实退回入库验收，未完成释放场景待3.7回补。文件：修改 `J/scheduling/aftersales/AfterSalesSchedulingService.java` 的createTask、AfterSalesSchedulingViews及TaskService内部创建入口；改名 `T/AfterSalesProductionTest.java` → `T/AfterSalesSchedulingTest.java`。
  - 做法：①专用POST /tasks接C2，锁内校验全部source属于路径case，类型/工种等于来源；②调用统一创建核心一次，不再次repository.arrange导致双占；③售后NORMAL和普通NORMAL共用3.3容量，REWORK只实际段；④普通任务入口故意传同source必须拒绝，不能借orderItemId伪装普通；⑤未完成与取消回同源同目标，不重新覆盖；⑥消费5.3已存在的下游等待sourceId创建等待计划，任务创建不新增授权、覆盖或实物流入。
  - 测试：`B(AfterSalesSchedulingTest)`；`sameTargetSourceSplitsTwoTwoLeavesOne`、`arrangesOnlyOnce`、`normalAfterSalesConsumesSharedCapacity`、`ordinaryEndpointCannotBypassCaseValidation`、`incompleteReturnsSameTargetWithoutCoverageGrowth`、`plansMakingAndPackingBeforeAnyFlow`。最后一项通过POST授权→GET来源→两次专用/tasks建立制作5及装袋等待5，覆盖仍5，装袋可执行0，后续6.3复用这条真实路径。
  - 接口：C4专用/tasks → TaskView；SOURCE_INVALID/SOURCE_INSUFFICIENT/CAPACITY_EXCEEDED/EMPLOYEE_NOT_ELIGIBLE；H/L。
  - 证据契约：source5→占4余1、只有两条分配、覆盖5不增、跨case/普通绕过拒绝、等待源与两任务ID/目标/流入0、核心及后补RED/GREEN；前端plans旧契约必须在8.1删除。
  - 人工证据：不适用（共享服务）；8.4验证从订单售后或返工池都走统一全页新建。

- [ ] 5.6 将售后库存领用接入终点预留或阶段正常加工
  - 依据/依赖：I1、O6；依赖3.2/5.2。现有`reverseMovement`继续作为既有逆向同步入口；本项不得删除、改名为取消HTTP或新增售后领用取消HTTP。文件：修改 `J/inventory/InventoryService.java` 的售后领用路径、`allocation/internal/InventoryAllocationRepository.java`、`J/orders/ledger/AfterSalesLedger.java`；新增 `T/AfterSalesInventoryAllocationTest.java`。
  - 做法：①先owner及来源/覆盖，后batch，修正旧先锁库存再反锁owner的路径；普通allocate共享竞争对象的锁序同步；②售后领用载荷在既有quantity上增加必填seamQuantity，校验批次产品/已完成工序/路线兼容及两类领用量分别≤路线gap，quantity≤库存，不用总gap抵另一类；③原子出库流水/减批次/售后接入/初始用途路线份额及覆盖；④终点库存直接RESERVED，非终点只写阶段实物流入并生成适用NORMAL来源，份额按C7贯穿；⑤补发确认不再扣该库存，回滚不留流水或来源；⑥现有`reverseMovement`逆向同步需覆盖全部`source/flow/coverage`关联，仅全部接入份额未真实加工、未被核验/下游/发货/补发/转库等消费且相关计划已显式解除时允许按既有规则逆向；任一份额已有这些事实即拒绝整笔，未核验或计划已取消不豁免，不依赖执行字段或新增人工确认，锁内重验售后归属，不新增HTTP或把它实现成取消接口。新增 `inventoryAllocationCannotCoverWrongRoute`；只有无缝gap时缝边成品不能混算补发。
  - 真实生产与查询：按C10经inventory自有InventorySchedulingPort及底层writer同事务写现有inventory_allocations/lines、出库movementLine及份额，不以售后ledger代替真实领用行。终点同时建立INVENTORY_INFLOW root/source、physicalShare及purposeAllocation，targetNode=SHIPPABLE仅追溯、不可排任务，balance/executable为0，terminalAvailableQuantity单独扣发货/转库/逆向；CaseView及来源GET返回真实ID与C5完成证据。非终点仍产生适用加工来源，不能伪造终点核验。
  - 测试：`B(AfterSalesInventoryAllocationTest,InventoryFulfillmentIntegrationTest)`；`finishedThreeIsDeductedOnlyOnceAcrossShipment`、`makingStockStillRequiresPackingAndSeam`、`twoAllocationsCompeteForOneBatch`、`coveredDemandRejectsAdditionalStock`、`finishedInventoryRootIsQueryableButNotSchedulable`；普通库存履约回归。
  - 接口：C4 inventory/after-sales-allocations含必填seamQuantity→流水摘要；STOCK_INSUFFICIENT/SOURCE_INVALID/售后数量错误；H/L。
  - 证据契约：原库存扣一次、非终点可补发0、两事务一成功、原订单台账不变、全回滚与RED/GREEN。
  - 人工证据：不适用（库存接入）；8.7检验只能选择兼容批次且展示剩余缺口。

- [ ] 5.7 为售后库存用途终点提供同事务实际入库接口
  - 依据/依赖：S21、I2/I3；启动依赖1.4及5.2覆盖核心，与4.5终点分支同批接线；退回入库核心验收依赖5.4/5.5退回建源及任务创建，不等待4.5整项完成。完整验收另依赖6.5/6.6/6.7的真实用途调整链。文件：新增 `J/inventory/AfterSalesInventoryReceiptService.java`；复用 `J/inventory/batch/internal/InventoryBatchRepository.java`、`movement/internal/InventoryMovementRepository.java`；核验流转服务调用；新增 `T/AfterSalesInventoryReceiptTest.java`。
  - 做法：①提供公开 `receive(ReceiptRequest)` 返回batchId/movementId，请求包含售后明细、实际来源、用途决定、终点核验/调整行、产品路线和数量；②加入调用者当前事务，不用独立事务；③明确仅两类互斥完成证据：加工链必须`completionEvidenceType=TERMINAL_VERIFICATION`且有真实`terminalVerificationId`；已完成成品直接领用进入RESERVED必须`FINISHED_INVENTORY_ALLOCATION`并关联真实batch/allocation/`inventoryAllocationLineId`/`inventoryMovementLineId`/`physicalShareId`/路线份额，不能以早期非终点库存接入代替终点核验；④校验确已到路线终点且INVENTORY用途，追加批次/入库流水并绑定唯一业务起因；⑤有退回链加引用，无退回NORMAL转库仅关联正常源/调整/终点，不造假FK；⑥批次不绑定目标订单，也不恢复旧发货批次；⑦同键重放只返回原结果，任一证据缺失/错链/越份额整笔回滚。
  - 普通余量接线：同项新增 `J/inventory/OrderSurplusReceiptService.java`，按C5 `receive(OrderReceiptRequest)` 返回inventory自有ReceiptResult；普通owner/orderChangeItemId与售后FK互斥，由scheduling适配器调用，不让orders反向import inventory。先交付共同入库核心，6.7接通普通混合变更处置并回补 `OrderSchedulingLifecycleTest`。核验/处置生成的入库拒绝通用孤立冲销，不新增核验或处置逆向；期初/独立调整合法冲销保留。
  - 测试：`B(AfterSalesInventoryReceiptTest)`；`repairTwoCreatesOneRealBatchAtTerminal`、`makingRepairDoesNotCreateStockEarly`、`receiptReplayDoesNotIncreaseStockAgain`、`normalWithoutReturnHasValidLineage`、`receiptFailureRollsBackVerificationAndCoverage`。
  - 验收分层：核心先由5.4生成库存用途退回源→5.5排任务→4.3/4.5真实核验落批次/流水，验证非终点拒入库、重放及失败整笔回滚；此核心供6.6/6.7调用，不要求无退回场景先通过。6.5/6.6产生无退回在制用途决定及终点核验后回补normalWithoutReturnHasValidLineage；6.7补合格转库调整起因，最后整类重跑并关闭本项。不能用手造调整行或假退回FK提前满足完整验收。
  - 接口：内部公开跨模块服务，无新增HTTP；错误回传原verify/corrections统一信封；库存唯一键冲突仅在同起因重放时读取原结果，不吞其他SQL错误。
  - 证据契约：核心退回链与后置无退回/调整链分别记录batch/movement/line/起因关系与库存实增、库存用途可补发0、失败无核验/流转残留、重放与RED/GREEN及剩余依赖。
  - 人工证据：不适用（终点入库）；9.3必须浏览器查到真实库存批次，不以“待入库”标签通过。

- [ ] 5.8 创建补发草稿即固化身份并专用确认整批归属
  - 依据/依赖：O1/O2/O6；依赖1.4/5.2。文件：修改 `J/orders/aftersales/AfterSalesService.java` 的createReplacementShipment/confirmReplacementShipment、AfterSalesRepository，`J/orders/shipment/internal/ShipmentRepository.java`；新增 `T/AfterSalesReplacementShipmentTest.java`。
  - 做法：①在同事务写shipment_kind、case_id、全部shipment items和after-sales links，禁止混普通行；草稿每行增加必填seamQuantity，保存两类数量快照，读取真实同路线预留作为候选，草稿不锁定消费份额；②专用确认先锁所属售后owner，再批次/明细与预留，核验每一行归当前case和order；③只允许DRAFT→CONFIRMED，逐路线重算预留/剩余需求，同路线按稳定份额ID消费并记关联，迁移RESERVED→SHIPPED，不能跨路线替代；④不调用普通ShipmentService.confirmInternal，不写原履约，不重复扣库存；⑤草稿不新增占用，确认竞争时重算。新增 `replacementConfirmationPreservesRouteBuckets`，有缝1/无缝1分别计已补，错误路线整笔拒绝。
  - 测试：`B(AfterSalesReplacementShipmentTest)`；`draftIdentityAndAllLinksAreAtomic`、`foreignCaseCannotConfirmAnyLine`、`confirmMovesReservationToShippedOnly`、`twoDraftsCannotConsumeSameReservation`；同键重放一次。
  - 接口：C4售后replacement-shipments与专用confirm → CaseView；SOURCE_INVALID/STATE_NOT_EDITABLE/售后数量错误；H/L。当前没有补发改量/作废/等量更正端点。
  - 证据契约：完整身份/归属/明细FK、原订购10/已发5/可发5与售后补2前后、库存不二扣、错case全回滚、RED/GREEN。
  - 人工证据：不适用（批次事实）；8.9核对前端只给合法动作。

- [ ] 5.9 为普通发货四个数量写入口增加补发身份拒绝守卫
  - 依据/依赖：O2；依赖5.8。文件：修改 `J/orders/shipment/ShipmentService.java` 的updateDraft/confirm/voidShipment/correct、ShipmentController/Repository；修改 `J/orders/aftersales/AfterSalesService.java` 的correct分派；新增 `T/AfterSalesShipmentIsolationTest.java`。
  - 做法：①对update/void/correct/logistics补传orderId并锁内核对路径归属；②在任何普通ledger/替代批次写入前检查持久化shipment_kind，不用hasAfterSalesOccupancy代替；③补发四入口统一STATE_NOT_EDITABLE，通用售后correct不可更改补发批次；④保留只读/物流，但物流请求不能改数量/身份/状态；⑤有售后受理的原普通发货仍按原占用规则，不误判补发。
  - 测试：`B(AfterSalesShipmentIsolationTest,ShipmentApiTest)`；`ordinaryWritesRejectReplacementDraftAndConfirmedBatch`参数化四入口；`logisticsAndReadRemainAvailable`；`originalShipmentWithAcceptedReturnsIsNotReplacement`；`wrongOrderPathCannotMutateShipment`。
  - 算例：原10/已发5/可发5，补发草稿2不能普通修改/确认；专用确认2后不能普通作废/更正，售后已补2及有效状态保持、无普通恢复或替代批次。
  - 证据契约：四条HTTP及通用更正绕过拒绝、原/售后非零台账不变、合法物流成功且数量不变、RED/GREEN。
  - 人工证据：不适用（隔离守卫）；隐藏按钮不是本项完成依据。

## 6. 补发需求调整：按未覆盖、授权、退回、在制和合格分别施工

- [ ] 6.1 新增结构化需求调整历史和未覆盖优先分支
  - 依据/依赖：O8、I3；依赖5.2。文件：修改 `J/orders/aftersales/AfterSalesService.java` 的correct分派和Views；新增 `J/orders/aftersales/AfterSalesDemandAdjustmentService.java`、`internal/AfterSalesDemandAdjustmentRepository.java`、`T/AfterSalesDemandAdjustmentTest.java`。
  - 做法：①接C3 targetType=REPLACEMENT_DEMAND，不再依赖beforeValue/afterValue自由文本；②按L锁定售后明细及请求引用，服务端读取真实before值、已补量和gap；③新需求不得低于shipped；增加仅记历史和gap，不自动授权/任务；④减少先消减gap，余下数量必须由处置行精确覆盖；⑤保存before/after/reason/operator/requestId和处置引用，响应更新CaseView。
  - 算法：按有缝/无缝分别计算 `reduction = oldRouteRequired - newRouteRequired`；减少分支 `coveredToDispose = max(0, reduction - oldRouteGap)`，增加分支只新增该路线gap。这里max仅表示已确认的减量分支，不掩盖负覆盖；各路线处置量须精确等于其coveredToDispose，且各自新需求不得低于已补发。保存总量及两类前后值；总量5不变但有缝2→3，是无缝减少1、有缝增加1，不得净额抵销。新增 `sameTotalRouteChangeDisposesCoveredOldRoute`、`cannotReduceRouteBelowItsShippedQuantity`。
  - 测试：`B(AfterSalesDemandAdjustmentTest)`；`fiveWithThreeCoveredReducesGapOnly`；`increaseTwoCreatesOnlyGap`；`shippedFourCannotReduceToThree`；`missingDispositionRollsBackDemandHistory`。
  - 接口：C4 corrections → CaseView；数量下限QUANTITY_INVALID、处置字段VALIDATION_INVALID、状态STATE_NOT_EDITABLE；H/L，保留既有合法更正分支。
  - 证据契约：旧/新/gap/覆盖/已补五列、结构化历史与原因、拒绝无历史残留、重放唯一、RED/GREEN。
  - 人工证据：不适用（调整骨架）；8.8要以服务端真实数量解释去向。

- [ ] 6.2 终止尚未排班且无实物的正常授权份额
  - 依据/依赖：O6/O8、I3；依赖6.1/5.3。文件：修改DemandAdjustmentService、SchedulingSourceService/Repository；新增 `T/AfterSalesAuthorizationTerminationTest.java`。
  - 做法：①TERMINATE_AUTHORIZATION只能指向确实无实物且未发生加工的NORMAL制作授权链；②按L锁内重验无退回/库存接入/核验处理/实际下游流入等业务事实，且所选份额在5.3生成的制作及各段等待源均无PENDING占用；不以未核验/计划取消推定未加工，不增执行字段或人工确认步骤，也不声称系统可感知未上报现场；③按同根路线份额收集各段授权，稳定锁定后逐段追加对应终止事实，覆盖只释放一次，不覆写total_quantity，不记报废/库存；④可安排量统一减累计终止，重复终止或安排超有效额拒绝，不能只终止制作却留下下游孤立额度；⑤需求再增加只出gap，5.3建新根及等待源，不能raiseTotal复活旧份额。
  - 测试：`B(AfterSalesAuthorizationTerminationTest)`；`unarrangedFiveToThreeKeepsOriginalFive`：无缝链制作/装袋各原5/终止2/有效3，覆盖合计3、各段可排3、库存0/报废0；`cannotAllocateOrTerminateRetiredQuantityAgain`；`newDemandUsesNewAuthorization`；`physicalOriginCannotUseTermination`。有缝份额仅终止所选路线对应额度，跨工序不重复释放覆盖。
  - 接口：C3 corrections，SOURCE_INVALID/SOURCE_INSUFFICIENT/STATE_NOT_EDITABLE；H/L，原授权与终止事实可重建余额。
  - 证据契约：5→3全列实值、库存/报废零增、再次安排/再次终止/伪装NORMAL拒绝、同键不双释放、RED/GREEN。
  - 人工证据：不适用（额度终止）；前端8.8应称“终止授权”，不能称报废或入库。

- [ ] 6.3 已排无实物减量要求显式取消全部关联等待计划
  - 依据/依赖：O8、S15/S16；依赖6.2/4.2及5.3同根等待授权、5.5等待任务创建核心。成功/遗漏场景必须通过5.1受理→5.3授权及GET选源→5.5专用/tasks建立真实制作/等待计划，不用SQL手造授权或任务。文件：修改DemandAdjustmentService、TaskService的同事务取消核心、source/flow关联查询；扩展 `T/AfterSalesAuthorizationTerminationTest.java`。
  - 做法：①先从来源链只读收集所有受影响制作及下游等待明细，比较请求cancelTaskItemIds是否完整且无外部owner；②按L一次锁全，重验无核验处理/实物流入等事实，无实物终止仍要求确实无实物且未发生加工，不把PENDING或取消资格当证明；③调用4.2同事务核心取消整条未核验计划，不经HTTP子请求、不检查日期或执行标记；④为涉及各段授权追加超出份额终止并释放覆盖一次，不能逐工序释放多次；⑤任意遗漏、关联计划已核验或存在实物/真实加工使该终止事务整笔回滚，不自动补选取消列表；这不妨碍单独取消未核验计划，不新增人工开工确认。
  - 算例：确实无实物且未发生加工的制作5+装袋等待5→需求3：两条原计划5均CANCELLED、原日期资源释放、对应有效额度3待重排；不能原地把计划5改3，也不能自动创建3。
  - 测试：`B(AfterSalesAuthorizationTerminationTest)`；`plannedFiveToThreeCancelsExplicitFullChain`、`omittedWaitingPlanRollsBackEverything`、`physicalProcessingCannotUseAuthorizationTermination`、`terminationAndVerificationSerialize`、`wholePlanQuantityStaysFive`；现场加工是业务边界描述，不通过虚构开始字段或请求造数，系统拒绝与回滚断言使用真实业务生产者可形成的实物/核验/消费事实。
  - 接口：C3处置cancelTaskItemIds，STATE_NOT_CANCELABLE/STATE_NOT_EDITABLE/VALIDATION_INVALID；H/L，共同事务覆盖需求/取消/终止/资源。
  - 证据契约：请求显式ID与真实全链集合、取消/终止各行、覆盖只释放2、未产生新任务/库存/报废、遗漏全回滚、RED/GREEN。
  - 人工证据：不适用（跨计划原子性）；8.8确认界面必须列出将取消的整条数量。

- [ ] 6.4 减量取消未核验退回修补计划时保留实物并要求合法去向
  - 依据/依赖：O8、I2/I3；依赖5.4/6.1/4.2。文件：修改DemandAdjustmentService、source用途分配写入及ScrapRecordRepository；新增 `T/AfterSalesReturnedGoodsDispositionTest.java`。
  - 做法：①对有退回实物的未排/已排来源禁止TERMINATE_AUTHORIZATION；②已排但未核验计划不论日期或现场加工均可显式取消，仍保留退回/source/原分配及真实流入/加工/消费；③CONTINUE_TO_INVENTORY追加用途决定并释放选中补发覆盖，保持目标及路线，取消后的有效份额等待重新排班而非立即入库；④SCRAP_RETURNED仅限未实际加工的退回实物且全部相关占用已显式解除，追加退回处置报废，不冒充缝边工序报废；可取消不证明未加工，已真实加工不得借取消进入此直接报废分支，不用执行标记或人工确认判定；⑤对当前source及全部对应未处理等待份额追加`source_invalidations`，当前失效份额不可复活、不可再安排，不按无实物授权终止处理；新增`disposedReturnedShareCannotBeScheduledAgain`；⑥处理失败连需求取消一起回滚，单独计划取消仍按4.2规则。
  - 测试：`B(AfterSalesReturnedGoodsDispositionTest)`；`cancelRepairDoesNotDeleteReturnedGoods`；`inventoryDispositionKeepsRouteAndWaitsForTerminal`；`scrapReturnedRecordsFactWithoutReplenishment`；`processedReturnedGoodsCannotUseDirectScrapBranch`；不得用虚构start字段构造真实加工证据。
  - 接口：C3 corrections，处置数量/归属与可用份额锁内重验；库存用途去向不再占补发覆盖，原客户需求不变。
  - 证据契约：取消后实物source仍在、用途转换/报废择一、有明确退回origin且无假缝边报废/提前库存、RED/GREEN与重放。
  - 人工证据：不适用（实物去向规则）；8.8呈现“继续修补入库/退回实物报废”而非无实物终止。

- [ ] 6.5 为待加工及实际在制的非终点有效份额追加用途而不修改原计划
  - 依据/依赖：S21、O8、I3；依赖6.1/3.1/5.2。文件：修改DemandAdjustmentService及3.1已建立的 `J/scheduling/source/SchedulingPurposeAllocationService.java` 和对应Repository；新增 `T/AfterSalesInProcessPurposeTest.java`。
  - 做法：①锁内找选中source/明细有效用途及路线份额，不以整条source用途覆盖全部；②复用3.1的初始份额核心，按C7收集整条同physicalShareId等待链并稳定加锁，追加原份额被新3/2份额替代及下游映射，不重写历史；③CONTINUE_TO_INVENTORY适用于真实退回、库存接入及前段合格的非终点有效份额：无任务、待加工或实际在制均可转换，保留当前阶段/路线，追加用途映射但不提前入库、不设开始字段或人工开工确认；④补发份额减少2并只释放一次对应路线覆盖，库存份额继续同路线，不制造库存；⑤用途转换本身保留制作及既有下游计划原量、日期、标准分钟和历史资源，不改变未核验计划可另行显式取消的资格；⑥无退回NORMAL允许走此分支，origin必须真实正常授权+adjustment；⑦已有下游消费须选当前有效后继，不能改已核验历史；⑧新增`nonTerminalInProcessShareCanConvertToInventory`、`returnedRouteSnapshotIsNotReplacementManufacturingSnapshot`、`sameSeamFlagDoesNotMakeDifferentProcessesCompatible`，明确原实物工艺snapshot与新制造snapshot隔离。
  - 测试：`B(AfterSalesInProcessPurposeTest)`；`inProcessFiveSplitsReplacementThreeInventoryTwo`、`waitingNonTerminalShareCanConvertWithoutRegistration`、`inventoryInfluxWithoutTaskCanConvertToInventory`、`qualifiedFrontStageWithoutTaskCanConvertToInventory`、`splitKeepsOriginalPlanAndCapacity`、`repeatedSplitCannotConsumeSupersededShare`、`normalWithoutReturnNeedsNoFakeReturnLink`。
  - 等待链验收：依赖5.3/5.5真实制作5+装袋等待5，新增 `purposeSplitPropagatesToExistingWaitingPlan`、`consumedDownstreamShareRejectsUpstreamAdjustment`；断言两段均映射补发3/库存2、原计划仍5、来源ID不变、加工总额不增、覆盖只减2、重放不重复映射。6.6继续核验该真实计划验证C7失效分支。
  - 算法：`sum(activePurposeShares.quantity) == originalPhysicalShare.quantity`；只对本次尚有效份额拆分，已被替代份额不可再用来处置。
  - 测试：`B(AfterSalesInProcessPurposeTest)`；`inProcessFiveSplitsReplacementThreeInventoryTwo`；`splitKeepsOriginalPlanAndCapacity`；`repeatedSplitCannotConsumeSupersededShare`；`normalWithoutReturnNeedsNoFakeReturnLink`。
  - 接口：C3 CONTINUE_TO_INVENTORY，CaseView及TaskView返回有效allocationId给核验；SOURCE_INSUFFICIENT/STATE_NOT_EDITABLE，H/L。
  - 证据契约：旧/新份额及替代关系、覆盖减少2/库存0/资源5、真实无退回关联、重放与RED/GREEN。
  - 人工证据：不适用（用途份额）；8.6核验要使用服务端allocationId而非自由选择用途。

- [ ] 6.6 混合用途核验逐份额守恒并分别流转返工与报废
  - 依据/依赖：S21、O8、I3；依赖6.5、4.3–4.7单用途结果处理及5.7已取证的入库核心，不等待5.7无退回完整验收；本项完成后回补5.7无退回NORMAL终点场景并回归4.3/4.5。文件：修改SchedulingVerificationService/Repository/Views及flow/source结果路由；新增 `T/SchedulingPurposeVerificationTest.java`。
  - 做法：①混合明细必须完整引用所有有效allocationId，拒外部/重复/已替代ID和客户端purpose；②逐组校验完成≤份额且≤锁内分配的可执行，各组完成量/返工目标按C2汇总匹配明细；③写verification allocation事实，按各组owner/purpose/route产生流转/返工源/报废；④仍有效未完成退对应份额来源，失效未完成标已处置且不回可排，不能全退原补发源；⑤库存报废不补做，补发报废依义务补做；整批任一组错全回滚。
  - 算例/测试：`B(SchedulingPurposeVerificationTest)`；`threeReplacementOneInventoryOneScrapKeepPurposes`必须复用6.5的既有装袋等待5真实链：制作补发合格3/库存合格1报废1，按C7同事务写库存报废1及下游失效1；原装袋计划仍5、可执行3/1，核验4后未完成1但可重排0，终点预留3/库存1，不补做，两个日期历史资源各5。新增 `invalidatedWaitingShareCannotBeRearranged`、`cancellingWaitingPlanDoesNotReviveScrappedShare`、`unarrangedWaitingShareInvalidatesOnce`、`waitingInvalidationFailureRollsBackVerification`；保留 `missingOrOversizedShareRollsBackBatch`、`groupTotalsAndReworkTargetsMustMatch`、`incompleteReturnsEachPurposeShare`。失效量仅标上游报废已处置，不能再进入未完成待重排提醒；普通有效未完成仍回同源。
  - 接口：C2 purposeAllocations；VALIDATION_INVALID/VERIFICATION_EQUATION_INVALID/QUANTITY_NOT_EXECUTABLE；H/L。仅唯一有效用途/路线份额可规范化，复用4.3同一结果处理器，不能写两套流转；同用途多路线仍须逐组。
  - 证据契约：组核验/子源/流转用途与数量、终点预留3/库存1、所有失败无部分组记录、幂等与RED/GREEN。
  - 人工证据：不适用（分组核验）；正式核验页面8.6按份额分栏且批量只发一次。

- [ ] 6.7 终点合格超出量原子解除补发预留并实际入库
  - 依据/依赖：O8、I3；依赖6.1、5.7入库核心、5.8补发确认核心及4.5真实终点预留；本项提供5.7完整验收所需的合格转库调整起因，不等待5.7整项勾选。真实成品领用的3→1保留2件新库存是本项必须回归，依赖5.6真实逆向/路线事实，不新增取消HTTP。文件：修改DemandAdjustmentService、AfterSalesCoverageLedger，调用AfterSalesInventoryReceiptService；新增 `T/AfterSalesQualifiedTransferTest.java`。
  - 做法：①TRANSFER_QUALIFIED_TO_INVENTORY仅接受同owner下已到终点、仍RESERVED且未SHIPPED份额；②按L锁owner/源覆盖/库存业务起因后重算，先未覆盖分支由6.1处理；③同事务写需求历史、用途决定、RESERVED释放和新batch/movement；成品领用3件由需求3改1时按真实allocation/line、movementLine、physicalShare及路线保留预留1、创建新库2，原领用只扣一次且不恢复旧批次；④入库失败整笔回滚，不能只先解除预留；⑤不恢复原发货批次，不增加已补发，不允许用此入口转已补发实物。
  - 查询到写入闭环：成品领用后从真实CaseView/来源GET取得SHIPPABLE sourceId/allocationId及terminalAvailableQuantity，按这些ID提交3→1转库；不手造source/核验，不把加工balance=0误判无终点份额。普通终点余量经C10同一全单Disposition锁计划调用5.7的OrderSurplusReceiptService，普通起因不带售后FK；混合ADD/增减/转库一次失败全部回滚。
  - 测试：`B(AfterSalesQualifiedTransferTest,OrderSchedulingLifecycleTest)`；`fiveQualifiedBecomesThreeReservedAndTwoInventory`、`finishedAllocationThreeToOneUsesReturnedSourceIds`、`shippedFourCannotReduceToThreeOrBeTransferred`、`receiptFailurePreservesDemandAndReservation`、`transferReplayDoesNotAddStockAgain`、`mixedOrderChangeUsesOneLockPlan`；回补3.1普通实物处置与5.7普通入库完整验收。
  - 接口：C3 corrections → CaseView；QUANTITY_INVALID/STATE_NOT_EDITABLE，H/L。
  - 证据契约：需求5→3/预留5→3/新库存2/已补不增、原批次不恢复、失败和重放SQL、RED/GREEN。
  - 人工证据：不适用（合格转库事务）；8.8确认必须显示真实将入库2而非仅改标签。

- [ ] 6.8 验证减量与取消、排班、核验、补发和库存的竞争
  - 依据/依赖：S24、O6/O8/O10、I1/I3；依赖5/6组全部行为，普通发货逆向核心由3.2/5.6先接通后在本项做并发回归。文件：新增 `T/AfterSalesSchedulingConcurrencyTest.java`、`T/AfterSalesProjectionRebuildTest.java`、扩展 `T/ShipmentRouteLineageTest.java`；修复发现的实际锁序问题。
  - 做法：①使用独立事务连接及屏障控制先后，不靠sleep；②覆盖授权终止vs核验、取消vs核验、减量vs新排班、用途转换vs核验、合格转库vs补发确认、库存接入vs退回补发用途、普通发货作废/等量更正逆向vs售后领用/转库；③普通发货作废仅按原shipment source links反向其自身消费、恢复同路线可发份额，等量更正将同份额传递给替代批次；遵守既有状态及有效售后占用限制，不因该次发货已消费或历史实际加工而拒绝，不恢复库存、不删除原链接。“任一接入份额已真实加工或被核验/下游/发货/转库等消费即拒绝整笔逆向”仅用于库存领用取消/接入逆向，不能套用于普通发货逆向或未核验计划取消；计划已取消不豁免该边界，也不放宽直接退回报废或无实物终止；④记录每条路径锁层顺序，失败方重验并完整回滚；⑤用事件重建source有效额、各用途份额、coverage buckets、库存余额及普通可发份额，逐case/root/目标/route对账；⑥同键重放每个新写端点，不只验证创建，保留已有`reverseMovement`语义，不新增开始字段/命令或人工确认步骤。
  - 测试：`B(AfterSalesSchedulingConcurrencyTest,AfterSalesProjectionRebuildTest,ShipmentRouteLineageTest)`；`terminationAndVerificationSerialize`、`cancelAndVerifyOnlyOneWins`、`transferAndShipmentCannotUseSameGoods`、`returnAndInventoryCannotDoubleCover`、`ordinaryReverseAndInventoryTransferSerialize`、`inventoryIntakeReverseRejectsProcessedOrConsumedShare`、`planCancellationDoesNotPermitPhysicalReversalOrDirectScrap`、`ordinaryShipmentVoidRestoresItsOwnConsumedShare`、`ordinaryShipmentCorrectionTransfersItsOwnConsumedShare`、`rebuildMatchesAdjustmentsAndPurposeShares`；库存逆向测试只针对原领用取消/接入冲销，使用真实核验/消费生产者，不伪造现场可观测性或执行登记。普通领用3→发货3在无有效售后占用且满足既有状态时，作废恢复可发3且库存不变；已关闭等量更正传递同份额、净交付不变；加工完成后发货亦不得被历史加工事实误禁。5→3和补2非零数据贯穿。
  - 接口/错误：C4 cancel/create/verify/corrections/库存/确认；失败返回对应状态/余额错误，不出现死锁后静默成功或部分事实。
  - 证据契约：双方请求/时序/结果、事务级前后实值、每个幂等端点覆盖表、事实重建差异0；超时与死锁必须归因修复后重跑。
  - 人工证据：不适用（并发/重建）；浏览器无法替代真实并发数据库验证。

## 7. 服务端制作工作量提示

- [ ] 7.1 集中实现精确有效分钟和未取消 NORMAL 制作汇总
  - 依据/依赖：S20、O4；3.3核心后可实现精确公式和分类聚合，局部SQL夹具仅验证查询；真实创建/取消/核验/售后分类完整验收需3.4、4.2、4.3、5.5。7.2只依赖本项已验证的计算核心，不等待全部分类场景关闭。文件：新增 `J/scheduling/capacity/SchedulingWorkloadService.java`；修改SchedulingTaskRepository的员工日期分钟聚合及 `J/catalog/settings/SettingsService.java` 的公开只读设置入口；新增 `T/WorkloadHintTest.java`。
  - 做法：①给SettingsService增加只读 `workloadBasis()`，同一条SELECT读取catalog_settings的workday_hours/making_effective_hour_rate及各自version；返回 `WorkloadBasis(BigDecimal workdayHours,BigDecimal makingEffectiveHourRate,long workdayHoursVersion,long makingEffectiveHourRateVersion)`，configurationVersion固定为这两个版本的对象，不用MAX(version)漏掉较小版本变化；不写业务常量，也不从订单历史快照读取当前有效工时基准；②按员工+日期+MAKING+NORMAL+非取消合计冻结estimatedMinutes，包含售后/补做/VERIFIED，排除REWORK/其他/装袋/缝边；③BigDecimal计算有效分钟、合计、差额和结论；④返回基准与分钟字段，前端只格式化显示。
  - 接口：内部 `calculate(long employeeId,LocalDate taskDate,long draftMinutes)` → `WorkloadHint(workdayHours,makingEffectiveHourRate,configurationVersion,scheduledMinutes,draftMinutes,totalMinutes,effectiveMinutes,differenceMinutes,conclusion)`；结论UNDER/EXACT/OVER。draftMinutes由服务端快照计算，不能用客户端传值。
  - 关键代码：`effective = hours.multiply(BigDecimal.valueOf(60)).multiply(rate); difference = BigDecimal.valueOf(totalMinutes).subtract(effective); int comparison = difference.compareTo(BigDecimal.ZERO);` 标准/估算分钟仍整数，有效分钟不提前round。
  - 测试：`B(WorkloadHintTest)`；`eightHoursAtSeventyFivePercentIs360`；330→-30/UNDER、390→30/OVER、360→0/EXACT；`fractionalEffectiveMinutesUseExactComparison`；`includesVerifiedAndAfterSalesNormalOnly`。
  - 证据契约：配置版本/比率实际存储尺度、三组算例和小数临界值、分类聚合原明细、RED/GREEN；无写入事务、无独立业务错误码。
  - 人工证据：不适用（计算）；8.3呈现余量/超出/刚好，不显示枚举英文。

- [ ] 7.2 增加无占用预览并精确豁免业务写入基础设施
  - 依据/依赖：S20/S24；依赖7.1/3.4。文件：修改SchedulingTaskController/Service；修改 `J/shared/web/WritePolicy.java`；新增 `T/SchedulingPreviewApiTest.java`。
  - 做法：①POST /preview接C2，从服务端来源/产品快照计算本次制作分钟，再调用7.1；②验证必要日期/员工/来源形状但不分配编号、不锁后占额度、不创建任务/业务事实；③WritePolicy仅增加POST精确`/api/scheduling-tasks/preview`豁免，不能以endsWith preview放行写命令；④仍要求认证、安全日志、requestId，产品两个既有preview豁免不回归；⑤同员工存在已排分钟时预览含草稿一次。
  - 测试：`B(SchedulingPreviewApiTest)`；`previewWritesNoTaskAllocationAuditOrIdempotencyRecord`；`previewStillRequiresAuthenticationAndRequestId`；`lookalikePathIsNotExempt`；`productPreviewPolicyRemainsExact`。限定当前requestId查审计/幂等，不检查全局表为空。
  - 接口：C4 preview → WorkloadHint；无需Idempotency-Key，非法输入VALIDATION_INVALID；不能因OVER返回CAPACITY_EXCEEDED，实际数量硬约束仍归创建。
  - 证据契约：请求前后任务/来源/编号/业务审计/幂等均不变、安全日志仍有、330/390响应、RED/GREEN。
  - 人工证据：不适用（HTTP只读）；8.3验证输入变化触发预览而非偷偷保存。

- [ ] 7.3 创建、详情和工作台复用同一计算并防已保存任务重复计入
  - 依据/依赖：S20、O4；启动依赖7.1计算核心、7.2预览及3.8已有事实读模型；取消/核验一致性的完整验收依赖4.2、4.3单用途核验，售后汇总依赖5.5真实NORMAL任务。不能在只有3.3容量核心时宣称本项可独立完成。文件：修改SchedulingTaskService.create/get/list、Views；扩展 `T/WorkloadHintTest.java`、`T/SchedulingPreviewApiTest.java`。
  - 做法：①预览scheduled不含草稿，draft由请求计算；②保存后所有已保存分钟都在scheduled，详情/列表调用draft=0，不再额外加本任务；③保存重读配置返回实际版本，若预览后设置变化用新版本但不覆盖历史明细标准分钟；④取消释放本计划提示量，核验不释放；⑤同员工日期列表各卡显示同基准，不多次累加卡片响应。
  - 测试：`B(WorkloadHintTest,SchedulingPreviewApiTest)`；`savedTaskIsNotCountedTwice`；`submissionRereadsConfigurationVersion`；`overWorkloadCanSaveButInsufficientSourceCannot`；`verificationKeepsMinutesCancellationReleasesThem`。
  - 算例：已排300+草稿30→预览330；保存后scheduled330/draft0/total330，绝不能360；另草稿90到390可保存，但同量超产品正常数量上限仍CAPACITY_EXCEEDED。
  - 证据契约：预览/保存/详情/列表四响应字段对照、配置变更前后版本与历史快照、来源硬拒绝和OVER软放行、RED/GREEN。
  - 人工证据：不适用（后端一致性）；8.2/8.3/8.5显示同一结论。

## 8. 正式前端页面与交互接线

前端测试沿用当前 Vitest + react-dom/server + fetch stub，不假定已有 Testing Library/jsdom，不为本次计划添加依赖。每项先用纯载荷/SSR/HTTP契约测试取得RED，再在真实浏览器走交互；SSR和typecheck不能冒充交互完成。视觉结论分别留待用户签字，可先连续完成全部页面和9.3自测再集中签字。

- [ ] 8.1 迁移前端类型、API、组件目录和正式路由
  - 依据/依赖：S1/S4/S23、O5；依赖2.2及C2/C3后端契约。文件：`F/api/production.ts` → `F/api/scheduling.ts`、`production-api.test.ts` → `scheduling-api.test.ts`；`F/pages/production/` → `pages/scheduling/`、`F/components/production/` → `components/scheduling/`；修改 `F/api/afterSales.ts`、`F/routes/routes.tsx`、`paths.ts`、对应测试、`F/pages/ProtectedLayout.tsx`、订单详情入口。
  - 做法：①Production页面/组件/function前缀统一Scheduling；②C2/C3类型与后端同字段，去旧purpose=REWORK/REPLACEMENT混类型、旧planNo/planType返回；③售后/plans改为/scheduling-sources/tasks，普通提醒去/incomplete后缀；④统一侧栏及跳转到正式/scheduling四路由，不保留临时预览路由；⑤删超额API/types/入口，保留其他排班与未完成。
  - 测试：`FTest(src/api/scheduling-api.test.ts)`、`FTest(src/routes)`；每个新函数断言method/path/body及信封解包；保护路由不退化匿名；旧plans/production/overtime调用零命中（拒绝测试/历史除外）。
  - 接口/错误：C4全表前端函数一一对应；继续apiFetch处理统一错误，不在各页新造fetch解包逻辑。
  - 证据契约：类型/函数/路由改名清单、fetch契约RED/GREEN、typecheck、正式入口浏览器导航与网络；区分只是接线和业务UI完成。
  - 人工证据：`pending-user-signoff`；检查正式侧栏“排班”可达四路由，订单上下文入口不跳旧页，无超额菜单；9.4集中签字。

- [ ] 8.2 周历同级展示两类排班并提供只读详情与显式取消
  - 依据/依赖：S1/S16/S19/S22/S23；依赖8.1/2.4/3.7/4.1/4.2/7.3。文件：修改 `F/pages/scheduling/SchedulingWorkbenchPage.tsx`、`SchedulingWeekCalendar.tsx`、`SchedulingTaskCard.tsx`、`SchedulingFilters.tsx`、`OtherScheduleSection.tsx`、`schedulingLogic.ts` 及工作台测试。
  - 做法：①以日期列为横轴，把制作和其他排班转同一日期事件列表，OtherScheduleSection不再置底独立大表；②保留今日视图、指标/问题态、日期高亮、每日添加；员工固定色Badge同行，状态第二行，员工为筛选不当横轴；③点击任务进入只读详情，工作台明细提供显式取消，未来/当天/过期的未核验计划同样可取消，不按现场加工隐藏或禁用；已核验显示不可取消原因，无登记开始入口；④显式取消填写原因，不增加人工开工确认步骤；核验后的未完成提示提供新建入口而非重验；⑤制作工作量只显示服务端结果，不将OVER变不可保存。
  - 测试：`FTest(src/pages/scheduling/SchedulingWorkbenchPage.test.tsx)`：同日两类事件、状态分组、无开始按钮/字段/请求、三日期未核验取消、已核验取消禁用及未完成提示保留。真实浏览器跨周/筛选/只读详情/三日期显式取消，并验证现场已加工未核验不构成取消限制、已核验直接HTTP拒绝，检查其他排班没有消失。
  - 接口：C4列表/其他排班/提醒/cancel/defer；刷新只更新读模型不二次写入；网络错误不乐观伪造取消成功。
  - 证据契约：正式/scheduling截图、两类同列DOM与请求、进入详情无写入、显式取消只写一次、三日期成功与已核验拒绝响应、无开始请求及控制台回归，typecheck/test/build。
  - 人工证据：`pending-user-signoff`；日期横向、两类同级、员工Badge/状态位置、工具栏紧凑、只读详情和取消意图清楚、已核验不可取消且无开工步骤；9.4签字。

- [ ] 8.3 全页统一新建正常任务和其他排班，接提交前工作量预览
  - 依据/依赖：S2/S5/S6/S20/S22/S23；依赖8.1/3.4/7.2/2.4，保存后工作量响应的完整浏览器验收另依赖7.3；不要求先完成8.2签字，同日周历联验在8.2接线后回补。文件：修改 `F/pages/scheduling/SchedulingTaskCreatePage.tsx`、`schedulingLogic.ts`、对应CreatePage测试。
  - 做法：①一级选择制作/其他，制作内NORMAL/REWORK；不使用抽屉、不预选业务来源；②正常选择多订单产品来源，显示可安排/可执行/目标，提交C2只送sourceId和计划量；③员工/日期/制作草稿改变时请求preview，忽略旧请求晚到覆盖新值，不保存草稿事实；④余量/超出/刚好及配置基准可见，提交取服务端最终提示；⑤其他分支员工/日期/事项/小时分钟使用已有OtherScheduleRequest，不送商品字段。
  - 测试：`FTest(src/pages/scheduling/SchedulingTaskCreatePage.test.tsx)`；`normalPayloadUsesSourceIds`、`otherScheduleUsesMinutesContract`、`previewDoesNotCallCreate`、`overHintDoesNotDisableSubmit`、`stalePreviewCannotOverwriteLatestSelection`。
  - 浏览器：两订单三产品一次创建；来源不足字段定位；8h/75%预览330与390分别显示余30/超30；其他1h30m保存后在同日周历；硬容量超限仍显示服务端错误。
  - 证据契约：表单前后截图、preview/create不同请求与最终响应、一次点击一次create、数据库无预览任务，类型/测试/构建及控制台。
  - 人工证据：`pending-user-signoff`；全页步骤明确、无超额入口、来源就近、工时软提示不冒充硬错误；9.4签字。

- [ ] 8.4 新建 REWORK 按目标来源选择并支持售后上下文
  - 依据/依赖：S9/S10/S23、O4/O5；依赖8.3/4.4/5.5。普通全局返工入口需能取得真实`afterSalesCaseId`；依赖2.2/C2统一SourceView，不以导航参数猜归属。文件：修改SchedulingTaskCreatePage、schedulingLogic及CreatePage测试；修改 `F/pages/orders/AfterSalesPanel.tsx` 的排班导航。
  - 做法：①REWORK列表显示发生入口/工序、目标、用途、路线、轮次、来源总额与余额，并从SourceView渲染`afterSalesCaseId`；②选择来源后目标决定工种，不能把所有返工固定到制作；③同任务仅同目标来源，跨case不混；普通源用普通create，售后源携case上下文用专用/tasks但共用表单与C2；④禁止独立“创建返工来源”操作，返工源只能来自核验；⑤同源可安排2再3，余额刷新，不阻止合法第二次安排。
  - 测试：扩展 `FTest(src/pages/scheduling/SchedulingTaskCreatePage.test.tsx)`；`reworkTargetControlsWorkType`、`afterSalesUsesCaseScopedTasksEndpoint`、`cannotMixCaseOrTargetInOneTask`、`noManualSourceCreationAction`。
  - 浏览器：缝边返工来源制作2/装袋3分别选正确工种；售后缝边来源可排；再返工目标由新来源显示；余额不足直接HTTP拒绝也可读错误。
  - 证据契约：来源列表实际ID/目标/用途、两个合法创建与跨case失败请求、无/plans及手工source POST、正式新建截图与控制台。
  - 人工证据：`pending-user-signoff`；返工发生工序与目标不混，售后只作上下文不新增顶级来源导航；9.4签字。

- [ ] 8.5 只读详情展示完整来源、核验、份额和稳定时间线
  - 依据/依赖：S3/S19/S21/S23；依赖8.1/3.8/6.5/7.3。文件：修改 `F/pages/scheduling/SchedulingTaskDetailPage.tsx`、`F/components/scheduling/SchedulingFactTimeline.tsx`、`SchedulingItemFactTable.tsx`，详情/时间线测试。
  - 做法：①以只读字段展示原计划、实际流入、可执行、核验、未完成、源/目标/用途/路线/轮次及核验/取消操作者与时间；②按有效用途/路线份额显示allocationId、3/2及调整引用，既有等待库存2中失效1须关联原报废并区分未完成1/可重排0，不把原计划改3或4；③报废发生工序与MAKING补做分列，终点库存显示真实批次/流水；④时间线key用type:id，格式化时间不暴露微秒原串/英文枚举；⑤只放显式导航到核验页，不在详情预置可提交表单；显式取消留工作台，不提供开始入口。
  - 测试：`FTest(src/pages/scheduling/SchedulingTaskDetailPage.test.tsx)`、`FTest(src/pages/scheduling/SchedulingFactTimeline.test.tsx)`；只读无提交控件、同ID不同类型不丢、非零售后来源不空、时间稳定。
  - 浏览器：普通10完成6、返工多轮、售后混合用途、库存终点四种任务逐条追溯；返回工作台筛选状态不被破坏。
  - 证据契约：正式详情截图/DOM、事实GET与屏幕逐字段对照、稳定排序及格式断言、无写请求、typecheck/test/build。
  - 人工证据：`pending-user-signoff`；读写分离、数量口径不混、关联事实易追溯、沿用白底Card与项目标题层级；9.4签字。

- [ ] 8.6 全页批量核验支持返工目标分配与混合用途组结果
  - 依据/依赖：S9/S13/S16/S17/S21/S23、O8；依赖8.1/4.3/4.4/6.6。文件：修改 `F/pages/scheduling/SchedulingTaskVerifyPage.tsx`、schedulingLogic和VerifyPage测试。
  - 做法：①只列可核验PENDING行并提示任务日之后、一次性及锁内可执行边界，无开始登记前置；②各行填写合格/返工/报废，缝边无报废输入且payload恒0；③返工按合法目标拆量，本地提示合计但仍送服务端守卫；④多个有效用途/路线份额按服务端allocationId分组录入，即使同用途不同路线也不合并，组用途与路线只读，生成C2组及聚合总数；失效量只读标已处置，不作为可填完成或可重排数量；⑤一次按钮仅一次verify请求，fieldErrors定位到明细/组/目标，整批失败明确“未写入任何核验事实”；成功不允许再次提交旧行。
  - 测试：`FTest(src/pages/scheduling/SchedulingTaskVerifyPage.test.tsx)`；`submitsAllItemsOnce`、`mapsErrorsToItemPurposeAndTarget`、`seamPayloadHasZeroScrap`、`mixedPurposePayloadKeepsAllocationIds`、`samePurposeDifferentRoutesKeepSeparateResults`、`invalidatedShareCannotBeSubmittedAsCompleted`、`doesNotSubmitPartialBatchOnError`。沿现有SSR/fetch模式，不假装它测到了点击。
  - 浏览器：缝边返工5分2/3；计划5用途3/2→合格3/合格1报废1；遗漏组/超可执行全回滚；完全未做的计划在任务日之后完成0；合法正完成无开始标记也可正常核验；同日/未来、已取消或已核验仍拒绝。
  - 证据契约：一次网络写请求、精确C2载荷和字段错误、失败库事实零增、成功各组去向、截图/控制台及全前端门禁。
  - 人工证据：`pending-user-signoff`；按组录入不混用途、返工分配完整、缝边没有报废、批量失败结果清楚；9.4签字。

- [ ] 8.7 售后只读面板增加显式退回核验、授权和库存接入流程
  - 依据/依赖：O1/O3/O6、I1/I2；依赖5.1–5.6/8.1。退回路线输入由5.1的`returnedSeamQuantity`生产；补发路线仍由`replacementSeamQuantity`独立输入。文件：修改 `F/pages/orders/AfterSalesPanel.tsx`、`afterSalesInput.ts`、`afterSalesInput.test.ts`、`F/api/afterSales.ts`；新增 `F/pages/orders/AfterSalesPanel.test.tsx`。
  - 做法：①面板默认只读，显式创建/退回核验/正常授权/库存领用后才出现输入；②退回和补发独立，默认相等但显式0保留，退回0也必须显式`returnedSeamQuantity=0`，补发其中缝边量必填，展示各自适用工艺且无工艺不得填正数；③退回核验按补发/库存用途、目标及原实物路线分配，直接报废按每条退回路线差额单列；④展示required/shipped/reserved/active/gap总量和有缝/无缝分项，不把未排来源当缺口；⑤授权填写quantity、其中seamQuantity及原因，不按比例回填路线；库存选择兼容批次，明确提交quantity及其中seamQuantity，展示阶段及匹配的路线缺口，不提前显示可补发；⑥普通/全局返工导航使用SourceView真实caseId，不能靠URL参数猜售后归属。新增 `submitsExplicitSeamDemandAndAuthorization`、`doesNotBorrowOtherRouteGap`、`submitsReturnedRouteQuantity`，以5其中缝边2、分次3其中缝边1的浏览器请求和持久化验证。
  - 实退与期初入口：核验显示预录两桶并显式填写实际returnedQuantity/returnedSeamQuantity，展示差异但不强迫先走correct；提交实际路线及各用途分配，失败保留输入。同步修改 `F/pages/inventory/InventoryPage.tsx`、库存API/表单测试，期初按C5显式routeSeamRequired及适用seamTypeId，与node/seamState区分；同批单路线/工艺，混合分批，不能只补后端字段而无真实生产入口。
  - 测试：`FTest(src/pages/orders/afterSalesInput.test.ts)`、`FTest(src/pages/orders/AfterSalesPanel.test.tsx)`及既有InventoryPage/库存API测试；新增 `submitsActualReturnRouteDifference`、`openingStockProducesFrozenRouteEvidence`，覆盖0与缺省区分、退5补3、5分2/1/1/1载荷、gap0不提供可成功授权假象、只有显式操作才出现表单。
  - 浏览器：无退回补发/只退不补/仅退款；退回分配错误服务端全回滚；未排补发源5→gap0；库存非终点3→可补发0；源授权不自动任务。
  - 证据契约：订单正式路由操作与API/库内覆盖对应、表单载荷、拒绝和成功响应、无原订单数量/应收变更、typecheck/test/build及控制台。
  - 人工证据：`pending-user-signoff`；默认只读、动作就近明确、用途与目标分开、未覆盖与可补发不混；9.4签字。

- [ ] 8.8 售后减量流程列出各类数量和必须处理的去向
  - 依据/依赖：O8、I3；依赖6组/8.7。`OrderChangePage.tsx`已存在的普通订单变更表单同步纳入C8 dispositions字段，不新增独立普通改版任务；本项仍仅新增/修改售后调整表单。文件：修改AfterSalesPanel和afterSalesInput；修改既有 `F/pages/orders/OrderChangePage.tsx` 的C8变更dispositions表单接线；新增 `F/pages/orders/afterSalesDemandAdjustment.ts`、`afterSalesDemandAdjustment.test.ts`（仅负责表单分组与C3组装，权威数量仍后端）。
  - 做法：①显式进入调整，按有缝/无缝展示原需求/已补/未覆盖/无实物授权/退回实物/在制/合格预留（依真实来源及消费事实展示，不派生隐形开工状态），同时输入新总量与其中缝边量；②逐路线解释未覆盖抵减，剩余份额要求逐条选择合法去向和allocationId，不默认静默转库，等总量换路线也不能省去向；③无实物已排列出将取消的制作及全部等待明细和原计划5，组装cancelTaskItemIds；④在制显示追加3/2及已存在等待计划的同步份额映射、继续加工才入库，失效部分标已处置而非待重排；合格显示立即预留3/入库2；⑤提交前合并一条数量警示和明确动作，错误保持输入且不伪造成功；服务端有并发变化时刷新数据后重选。新增 `sameTotalRouteChangeRequiresDisposition`、`showsExistingWaitingPurposeShares`，完整C7整链沿正式页面复验。
  - 测试：`FTest(src/pages/orders/afterSalesDemandAdjustment.test.ts)`；`buildsExplicitCancellationList`、`requiresDispositionForCoveredReduction`、`neverTerminatesPhysicalGoods`、`purposeChangeKeepsOriginalPlanWhole`、`cannotReduceBelowShipped`。
  - 浏览器：无实物未排5→3、已排5→3、遗漏等待计划拒绝、有实物不可终止、在制3/2、终点合格3/2、已补4改3拒绝；另走期初成品→领用3→GET真实SHIPPABLE来源→需求1/新库存2，UI以terminalAvailableQuantity列候选而非balance，且新建任务不提供SHIPPABLE；普通变更双同商品ADD与增减/转库全单提交，保留真实起因映射；验证每次失败整笔未写。
  - 证据契约：七分支截图/载荷/库内前后、真实计划ID、余3待重排无自动任务、无实物库存0/报废0、控制台与门禁。
  - 人工证据：`pending-user-signoff`；能看懂取消整条5后余3须重排，区分授权终止/实物报废/继续入库/合格入库；9.4签字。

- [ ] 8.9 补发批次只呈现合法数量动作并保留只读/物流
  - 依据/依赖：O1/O2；依赖5.8/5.9/8.7。普通发货的`seamQuantity`/来源只读生产与本项同步，不能只覆盖售后面板。文件：修改 `F/pages/orders/AfterSalesPanel.tsx`、`OrderDetailPage.tsx`、现有 `F/pages/orders/ShipmentPanel.tsx`、发货API类型与现有发货动作渲染；扩展AfterSalesPanel测试及普通发货表单测试。
  - 做法：①按服务端shipment_kind/case归属识别补发，不凭备注或afterSales占用；②售后仅创建草稿/专用确认，草稿逐行输入quantity及其中seamQuantity并展示两类预留，确认按服务端重算结果处理，不能将草稿当已占用；普通改量/确认/作废/更正不对补发显示；③保留补发详情及物流修改；④普通原发货有售后受理仍显示其合法动作/已有占用拒绝理由，并在`ShipmentPanel.tsx`显示普通发货seamQuantity与只读来源链接；⑤直接HTTP守卫失败用STATE_NOT_EDITABLE文案，不靠隐藏按钮代替后端校验。
  - 测试：`FTest(src/pages/orders/AfterSalesPanel.test.tsx)`；`replacementUsesDedicatedConfirm`、`hidesUnsupportedQuantityActionsButKeepsLogistics`、`originalAcceptedShipmentIsNotReplacement`。
  - 浏览器：原10/已发5/可发5另补2，创建/专用确认/物流；四个普通写入口程序化拒绝后重新刷新原5/5、售后已补2不变。
  - 证据契约：补发身份响应和可用动作DOM、专用确认路径、物流成功、四普通失败与台账、控制台和前端门禁。
  - 人工证据：`pending-user-signoff`；普通发货与补发不混、只读物流保留、无未实现补发逆向动作；9.4签字。

## 9. 跨模块收尾、正式路由自测与签字

- [ ] 9.1 同步报表、运维铺数、架构和逐场景追踪
  - 依据/依赖：全部S/O/I与旧production能力移交；依赖2–8组实现可取证。文件：修改 `J/reports/ReportService.java`、ReportViews与 `T/ReportApiTest.java`；修改 `docs/architecture/acceptance-traceability.md`、四份现行架构文档、`docs/delivery/acceptance-seed.sh`、`manual-acceptance-report.md`；核查release/preflight/backup/restore/check-alert脚本实际命中处后定点改。
  - 做法：①扫描production、overtime、旧sources/plans、假枚举、恒0/null、废错误码和旧表名，把运行引用/拒绝测试/历史归档分类；②报表改真实来源/工序/用途归属，不合并普通与售后需求或各工序合格；③铺数脚本用真实新API，覆盖未核验显式取消/任务日之后核验与新授权/用途分配，无开始登记步骤，不能硬写近似表绕规则；④逐Scenario补接口→实现方法→测试方法→证据位置；⑤历史报告标取代关系，不改旧证据为新完成。
  - 测试：`B(ReportApiTest)` 非零普通/售后/返工/库存数据交叉对账；`bash -n docs/delivery/acceptance-seed.sh`（仓库根）；逐运行脚本只做授权范围内验证，不因此部署。旧18条移除名称继续和已发布production基线一致。
  - 证据契约：全仓命中分类、每个报表字段来源与实值、脚本语法退出码、Scenario到测试的无遗漏映射、文档现状/目标措辞；不将历史归档移除。
  - 人工证据：不适用（文档和报表数据）；9.3/9.4保留原移交工作台/详情/端到端的重新验收。

- [ ] 9.2 跑完整后端和前端门禁，再确定浏览器验收基线
  - 依据/依赖：S24与全部回归；依赖全部实现及9.1。文件：本项仅补本change逐任务证据与 `docs/delivery/manual-acceptance-report.md`；发现缺陷回相应任务修改，不另开隐形范围。
  - 做法：①外部注入YUMI_DB_PASSWORD，在backend执行`./mvnw test`；②frontend执行`npm run typecheck`、`npm test`、`npm run build`；③仓库根执行`git diff --check`和`openspec validate restructure-scheduling-module --strict`；④逐个记录退出码/测试总数/失败原因，既有失败必须证据归因，不只跑相关测试；⑤全部通过后才按授权重建/铺验收数据，记录测试专属数据ID与时间，避免用户并发改动混淆。
  - 验收：后端、前端、构建、差异及规格命令全部满足；对共享库执行了清理的测试后不得复用旧截图/旧数据编号。
  - 接口/事务：无新API，不能用临时禁用校验/跳过测试/改权限让门禁变绿；Testcontainers豁免原样保留，本机MySQL不能写成容器通过。
  - 证据契约：每条完整命令/工作目录/退出码/统计、失败归因与复跑、当前构建版本及脱敏环境、验收基线ID；没有实际输出不填“通过”。
  - 人工证据：非视觉；清库或重新铺数影响用户数据时须另获授权，机器门禁不代替用户签字。

- [ ] 9.3 启动正式页面并逐链路完成浏览器主路径、边界与失败自测
  - 依据/依赖：全部S/O/I浏览器可验证部分、O9/O10/O11；依赖9.2、8组已接线。普通订单变更/原发货/逆向、成品3→1转新库、全局返工链必须在正式链路中取证。文件：回填 `docs/delivery/manual-acceptance-report.md` 与本change evidence-template；使用 `docs/delivery/acceptance-seed.sh` 已授权基线。
  - 做法：①在backend用`./mvnw spring-boot:run`、frontend用`npm run dev`，先检查现有端口/实例，不杀他人进程；VITE_API_TARGET使用实际后端地址，登录会话重启失效需重新建立；②正式/scheduling和/orders/:id操作，不拿预览路由验收；③走下表全部链路，逐步记录请求/幂等键/响应/库内事实/控制台；④用户并发修改数据时查审计写入者/时间，复测固定基线；⑤Electron复用入口单独取证，无法启动必须如实列未验证，不宣称浏览器等于桌面通过。
  - 浏览器链路清单：A 普通确认/ADD多订单三产品→下游等待0→实际上游6→次日核验；B 普通增减10→12→8、等总量换路线及取消/关闭守卫；C 未来/当天/过期及现场已加工未核验计划均可取消、已核验拒绝、取消与核验互斥→另一计划10完成6→余4新计划且旧资源10，正完成无开始前置且完成0合法；D 同兼容池按taskDate/itemId稳定分配：后建更早A=5/B=0、后到2为A=5/B=2、A核验3释放有效未完成2后B=4，再到3后B最多5且未分配2，读与核验同算法、无B保护/未来绑定；E 缝边8合格2分制作/装袋→多轮→重过缝边→最终10；F 装袋报废2→制作NORMAL补做→反复报废后原等待只承接一次；G 未排售后源5覆盖满→另实物拒覆盖→库存用途全路线实际入库；H 退回路线输入/差额报废与补发新snapshot隔离；I 无实物未排/已排5→3→终止后增2显式新授权；J 在制5拆3/2→分组核验3/1/1→预留3/库存1；K 合格5→3预留+2库存、已补4改3拒；L 期初成品领用3→需求1→新库存2、原领用不恢复，领用逆向与转库并发互斥；M 原10/5/5另补2→普通四入口拒绝、专用确认和物流合法，普通ShipmentPanel显示seamQuantity/只读来源；N 制作330/390预览软提示、容量硬拒绝、其他90分钟核验并更正105；O 同键重放关键写入与失败后的页面恢复；P 全局返工入口显示真实case归属、非终点待加工转换及C8普通变更dispositions表单。
  - 验收：除必须用户登录/签字外全部自测；每条都有真实非零事实和错误结果，界面金额/日期/枚举展示、空列表、无合格库存、失效来源和网络错误也检查。C9规则已明确，1.1文档/实施契约核对仍未完成；全部场景须实际取证，不以文档同步冒充测试通过；不通过回对应任务修复，再跑受影响门禁并重建对应基线。
  - 证据契约：A–P逐步截图/网络/SQL、正式路由、构建版本、控制台异常0或明确归因、桌面复用结果；规格场景完成清单与失败回滚SQL，不只“点开页面”。
  - 人工证据：`pending-user-signoff`；机器自测可全部完成但本项和8组视觉项仍不勾选，等待9.4用户在正式入口确认。

- [ ] 9.4 集中用户视觉签字并回填逐项状态
  - 依据/依赖：S1/S20/S23与原移交工作台/详情/批量核验/端到端；依赖9.3机器自测完成。文件：修改manual-acceptance-report中本次排班重构块、本文件8.1–8.9/9.3证据状态，不覆盖旧报告结论。
  - 做法：①给实际可访问的正式入口与基线订单/任务编号，不使用已删除铺数样例；②逐条展示8组“人工证据”清单和已知未验证项；③用户明确确认后写`status: confirmed`、`confirmedBy: chen`、实际confirmedOn及conclusion；④只勾满足全部机器+人工条件的项，未确认项继续pending；⑤签字不自动授权commit/push或发布。
  - 验收：humanVisualConclusion清单含日期列/两类同级/统一新建/制作提示/只读详情/目标及用途核验/售后去向/补发隔离；机器证据对应当前正式版本，不以旧阶段5签字代替。
  - 证据契约：每个视觉任务的报告锚点、真实用户确认原话与日期、status/confirmedBy/confirmedOn/conclusion四字段一致；无测试RED/GREEN（签字记录任务）。
  - 人工证据：阻塞；必须用户明确确认，助手不得代签。用户未确认前本项保持未勾选。

- [ ] 9.5 完成双向追踪和最终规格检查，归档另按明确请求执行
  - 依据/依赖：全部S/O/I、O9/O10/O11、production-management 18条REMOVED；依赖9.4及所有实施项证据满足。覆盖表按固定Requirement标题/编号核对，不按文件出现顺序推断；普通变更、普通发货/逆向、成品3→1转新库和全局返工链不得留在笼统后补。文件：本change tasks/evidence、现行acceptance-traceability；归档时才更新发布specs，不在实施中提前改基线。
  - 做法：①逐条核对下方覆盖表到真实实现/测试/人工结论，新增文件必须已存在，删除文件命中仅合法历史/拒绝测试；②检查每任务证据契约与人工证据均有实际回填或明确不适用理由，不能以文档条目代替证据；③根目录运行`openspec validate restructure-scheduling-module --strict`与`git diff --check`；④用户要求归档时才调用归档流程，移除production能力、增加scheduling并合入order/inventory delta，其余能力保留；⑤没有commit/push请求不提交。
  - 验收：18条REMOVED标题与原基线逐字匹配，活动specs的全部新增/修改Requirement及其Scenario按实际标题逐项具有测试/人工锚点；无“实现完成但路径不存在”、无未签视觉项被勾选。任务细化本身的严格校验不算本项完成。
  - 证据契约：覆盖差集为空、所有实际证据路径检查、最终严格校验/差异退出码；如归档记录真实命令和产物，否则明确“未请求归档”。
  - 人工证据：不需要新增视觉签字；归档、提交、推送、发布各有独立授权边界，不把本项完成解释成授权。

## 执行依赖与集成顺序

编号用于追踪，不是串行排队顺序。下列“核心”均须有真实实现和指定范围的行为证据；集成批次允许尚未勾选的生产者与消费者连续施工，不允许靠stub、手造业务事实或省略后置用例宣称整项GREEN。文档/实施契约核对、数据库授权和人工签字仍是独立门槛，已明确业务规则不再等待审批。

### 启动、核心交付与完整验收矩阵

| 任务 | 可先交付、供后续消费的范围 | 完整验收的真实生产者与回补时点 |
| --- | --- | --- |
| 1.3/1.4/1.5/2.1 | 先1.5授权旧库测试窗口、旧V1/代码取结构RED，再同批改V1/包/SQL引用；授权重建后启动、Clock及约束GREEN；2.1前移运行`ModuleStructureTest`并按C10验证端口编译DAG及真实Bean依赖 | 1.3/1.4结构断言与1.5Clock不等待业务闭环；不能以checksum失败作RED；2.1创建/编号待3.1/3.4，非零报表待9.1，C10端口实现不能以空实现替代核心就绪；不能将启动通过当行为全绿 |
| 2.2 | 路由映射、类型/字段、认证与旧入口退役；结构无环由2.1前置`ModuleStructureTest`覆盖，使用C10调用方自有DTO及真实适配器 | 3.1来源生产、3.4创建及最小详情返回后验证GET选源→POST真实任务；不把C10设计挪入本项 |
| 2.3 | 删除超额代码及旧映射/类型拒绝 | 未完成保留及普通数量不变需3.4、3.7、4.3，其他排班保留由2.4验证 |
| 3.1/3.2 | 3.1先交付普通无实物确认/ADD/变更基础；3.2在5.1前交付普通领用、取消、原发货路线与可执行流入核心；算法/查询层夹具不冒充业务端到端 | 3.1普通变更实物分支待4.2/4.7及6.5/6.7通用能力回补；3.2原发货路线核心不等5.9，逆向在5.6/6.8补齐；3.4创建/3.7未完成/4.3核验产生消费事实，4.5合格流入、4.7报废及5.5售后任务补齐相关HTTP断言 |
| 3.3 | 容量实体锁、分类聚合及批量校验核心 | 3.4提供真实并发创建；4.3/4.2提供核验保留与取消释放事实；5.5提供售后NORMAL共享上限场景 |
| 3.4/3.6 | 来源驱动创建、最小TaskView、冻结快照、来源并发和幂等 | 不等待3.8全时间线；3.8在已有创建响应上增量扩展，不以创建先调用未实现get形成循环 |
| 3.7/3.8 | 4.3核心后接入零完成/未完成释放与提醒；3.4后读已有事实 | 4.4–4.7、5.5、6.2–6.7补返工/售后/终止/份额/库存事实；3.7与4.3单用途结果同批验收，3.8在6组结束后补全时间线 |
| 4.1/4.2 | 普通授权的未核验取消资格、无开始操作契约及取消核心 | 正常核心先验收；返工取消待4.4，真实下游流入不被取消待4.5，售后覆盖不变待5.3/5.5 |
| 4.3 | 日期、未取消未核验、一次性、等式、零完成及原子事务核心 | 4.4–4.7、3.7及5.5齐备后完成单用途；6.6完成混合用途后再次回归，不要求这些消费者先等4.3勾选 |
| 4.4/4.5/4.6/4.7 | 按顺序接返工源、普通/售后合格流转、等待承接/重过、报废补做 | 4.4核心先给5.4，5.5提供售后任务；4.5库存终点与5.7核心同批接线；多轮真实核验在4.6/4.7就绪后回补4.4，不以纯建源测试替代 |
| 4.8 | 普通来源/流入/资源重建可先做 | 两个售后owner依赖5.1/5.3/5.5，多轮依赖4.4–4.7，终止及用途份额依赖6.2–6.7；完整重建放在6组闭环之后 |
| 5.1/5.2 | 1.5定义及3.2普通库存领用接通真实原发货夹具→受理owner及显式补发路线；分路线覆盖迁移/锁内gap核心 | 5.1核心须真实普通发货确认→受理通过，不用SQL造发货且不等4.5；5.2多阶段/报废待4.5–4.7，终点及确认待5.7核心/5.8；每份物理份额只覆盖一次 |
| 5.3 | 显式根授权及同根下游等待额度、GET真实来源、一次覆盖 | 5.5验证可安排但无流入，6.2验证终止后增量新授权，6.3验证整链等待额度终止；这些场景不阻塞先交付授权核心 |
| 5.4/5.5 | 退回按用途/目标建源；专用任务创建及零实物等待计划 | 5.4消费4.4建源核心；5.5需3.4/5.3/5.4，未完成回源待3.7。5.5创建核心必须先于5.7退回入库验收 |
| 5.6/5.8/5.9 | 库存领用、保留既有`reverseMovement`逆向同步、补发身份与专用确认、普通入口隔离；不新增售后领用取消HTTP | 5.8预留可由5.6真实成品库存领用产生，两项同批回归“确认不二扣”；加工终点场景由4.5/5.7核心补齐，5.6逆向覆盖source/flow/coverage且真实加工/消费拒绝由6.8回归，5.9在5.8核心之后验证四入口 |
| 5.7 | 与4.5同批完成退回库存用途核验→真实入库/防重/回滚 | 无退回NORMAL在制转库待6.5/6.6，合格预留转库待6.7；后两项只依赖入库核心，不等待5.7整项勾选 |
| 6.1/6.2/6.3 | 调整及未覆盖分支→未排授权链终止→显式全链取消后终止 | 6.1已补下限需5.8；6.2依赖5.3授权链；6.3须5.3/5.5真实产生等待源和计划，4.2取消核心就绪 |
| 6.4/6.5/6.6/6.7/6.8 | 3.1初始份额核心→退回去向/当前source及等待链失效→无任务、待加工或实际在制的非终点用途替代→组核验/失效→合格转库→并发及重建 | 6.4依赖5.4/5.5与4.2；6.5用5.3/5.5生成制作和等待计划并覆盖无任务非终点转换；6.6验C7真实失效及无复活，回补3.1余额、3.7提醒、4.2取消、4.7库存报废；6.6/6.7消费5.7核心后回补入库整链，再做6.8普通逆向/售后竞争 |
| 7.1/7.2/7.3 | 7.1在3.3核心后可做公式/分类查询；7.2等待3.4草稿快照；7.3等待3.8已有读模型 | 7.1分类及7.3一致性需5.5售后NORMAL、4.2取消、4.3核验真实事实；不能声称整个7组在3.3后独立完成 |
| 8.1–8.9 | 按各项API生产者并行做载荷/SSR/接线；正式浏览器验证等待相应真实后端 | 8.2需3.7/4.1/4.2/7.3，8.3需3.4/7.2且最终保存提示需7.3；8.4–8.9按逐项依赖取证，人工签字统一等9.4，不阻塞9.1机器收尾 |
| 9.1–9.5 | 9.1可先逐模块同步，2–8组机器实现齐备后收齐；9.2完整门禁→9.3自测→9.4签字 | 9.5等待全部机器及人工证据；归档/提交/推送仍各自独立授权 |

### 推荐集成批次

1. **契约核对与基线**：1.1 → 1.2 → 1.5前置连接/作用域授权；保持旧V1及旧生产代码先写并运行1.3/1.4可编译结构契约，取得旧基线行为RED并保存；再同批改1.3/1.4 SQL、2.1包/SQL调用与1.5Clock，在另行明确获准的重建边界迁移、validate、启动/Clock及结构GREEN。2.1前移运行`ModuleStructureTest`，按C10实现导出端口/DTO、适配器及底层writer分层，补真实上下文唯一Bean门禁。不得改V1后才连接旧库取RED，不关闭Flyway校验；不要求尚无3.1/3.4/5.3生产者的新业务全绿。
2. **普通生命周期与创建基础**：2.2–2.4核心、3.1普通确认/ADD/增减/取消的无实物链→3.2普通领用/取消及原发货路线→3.3、3.5→3.4→3.6；3.1普通无实物先交付，实物变更待4.2/4.7及6.5/6.7通用能力回补；3.2原发货路线核心必须在5.1前，不等待5.9，逆向后补。3.8先接已有事实，随后回补2.2读源→创建和2.1创建/编号。
3. **售后基础及核验入口**：先接通1.5定义、3.2适配的真实订单确认→期初成品入库→普通库存领用→普通发货确认夹具，取得真实shipmentItemId；5.1受理（含returnedSeamQuantity）及显式补发路线→5.2分路线覆盖核心→5.3授权/等待核心。4.1→4.2核心→4.3核验核心，3.7同步接未完成释放。4.4建源核心→5.4退回建源→5.5专用创建核心。至此真实普通/售后任务及零实物等待计划可用于后续闭环，不等待4.5生产合格造原发货。
4. **单用途加工闭环**：4.5普通/售后流转与5.7两类Receipt证据/退回入库核心同批接通→4.6→4.7；反复补做报废承接原等待仅一次；回补4.4多轮、4.3单用途、3.7、5.5未完成及3.1/3.2/3.3/4.1/4.2相关事实断言。4.8先验已有事实，不提前关闭完整重建。
5. **库存与补发**：5.6库存领用及已有`reverseMovement`全关联逆向同步→5.8专用确认→5.9普通写隔离；保留既有逆向能力，不新增售后领用取消HTTP。回补5.6不二扣库存、真实加工/消费拒绝逆向、5.2覆盖迁移及确认场景。库存接入与加工闭环核心就绪后可并行，但最终证据均用真实来源/批次。
6. **调整与后置整链**：6.1→6.2→6.3；6.4、6.5可在各自前置满足后独立实现，包含无任务/待加工/实际在制的非终点用途转换及当前source失效不复活；6.5→6.6，6.7消费5.7入库核心及5.8确认核心，覆盖成品领用3→1转新库存2；回补5.3终止后新授权、5.7无退回及合格转库，并回归4.3/4.5混合用途；按C7回补3.1失效余额、3.7失效未完成、4.2取消不复活及4.7库存报废下游失效；再完成6.8、4.8、3.8全事实覆盖。
7. **工作量与正式页面**：7.1计算核心可在3.3后开展，7.2需3.4，7.3需3.8已有读模型，完整7组等4.2/4.3/5.5真实事实。8.1→8.3/8.2（分别按自身前置），8.4依赖8.3并从SourceView取得case，8.5/8.6依赖对应后端，8.7→8.8/8.9且8.8接既有OrderChangePage普通变更表单、8.9同步ShipmentPanel普通发货；不为等待视觉签字停止机器取证。
8. **收尾**：9.1补报表/脚本/追踪并关闭2.1报表回归等剩余后置项，按固定Requirement标题/编号补全所有Scenario→9.2完整门禁→9.3正式页面自测（含普通变更/发货/逆向/成品3→1/全局返工链）→9.4用户签字→9.5。任何回归失败回到对应任务；会清理共享库的测试重跑后，必须重建浏览器基线并更新实际编号。

跨任务接口及份额承接按C1–C10落定，接口声明不能代替生产者实现。每个集成批次结束核对“核心证据、未运行/失败项、回补依赖、最终关闭时点”，没有具体回补任务的待验项不能留作笼统“后续补测”；消费者通过也不能替代生产者整项验收。

## 原34项到细化任务的迁移表

原编号仅用于理解旧讨论，不再作为执行锚点；新增任务不承接旧勾选或旧签字。

| 原编号 | 新施工编号 | 拆分重点 |
| --- | --- | --- |
| 1.1 | 1.1、1.2 | 已明确规则的文档/实施契约核对与物理锁/字段设计分开 |
| 1.2 | 1.3、2.1 | 单V1结构与代码SQL改名协同切换 |
| 1.3 | 1.3、1.4 | 单行CHECK、跨表引用、事实唯一键分工 |
| 1.4 | 1.4 | 来源/覆盖/用途/库存真实关系测试 |
| 1.5 | 1.5 | 库授权、迁移、时钟、夹具和清理顺序 |
| 2.1 | 2.1、8.1 | 后端模块/计算器与前端正式路由分开 |
| 2.2 | 2.2、5.3、5.5 | 真实API、显式授权、专用来源任务 |
| 2.3 | 2.3、2.4、8.1 | 删除超额但保留其他排班与未完成 |
| 2.4 | 2.2、2.3、9.1 | HTTP黑盒退役和全仓运行引用扫描 |
| 3.1 | 3.1–3.4 | 可安排、可执行、容量实体锁、整批创建 |
| 3.2 | 4.1–4.3、3.7、3.8 | 无开始操作、未核验取消、一次核验、未完成、读模型 |
| 3.3 | 3.5、4.3、4.4 | 目标矩阵、缝边守卫、原子分配 |
| 3.4 | 4.4、3.6 | 多轮来源与拆分/竞争/幂等 |
| 3.5 | 4.5、4.6 | 实际合格路由与等待承接/重过额度 |
| 3.6 | 4.7 | 报废事实与条件MAKING补做 |
| 3.7 | 3.6、4.8、6.8 | 普通/售后并发及多维投影重建 |
| 4.1 | 5.1 | 受理边界及退回/补发/退款独立 |
| 4.2 | 5.4 | 一次退回用途目标分配 |
| 4.3 | 5.2、5.3、5.5 | 覆盖台账、显式正常授权、仅消费现源排班 |
| 4.4 | 4.5、5.6、5.7 | owner终点路由、库存领用、真实入库 |
| 4.5 | 5.8、5.9、6.1–6.7 | 补发身份/入口隔离与七种调整施工分开 |
| 4.6 | 5.1–5.9、6.1–6.8 | 测试贴回每个业务交付，不只阶段末大测试 |
| 5.1 | 7.1 | 精确公式、真实配置版本及汇总维度 |
| 5.2 | 7.2、7.3 | 只读基础设施豁免与多入口不重复计入 |
| 5.3 | 8.2、8.3、8.5 | 工作台/新建/详情分别取证 |
| 5.4 | 7.1–7.3 | 算法/预览副作用/配置一致性测试 |
| 6.1 | 8.1、8.2 | 路由迁移、同级周历、只读详情与显式取消 |
| 6.2 | 3.8、8.5 | 完整真实读模型与只读详情 |
| 6.3 | 8.6、6.6 | 批量核验UI和用途分组后端独立验收 |
| 6.4 | 8.3、8.4、8.7–8.9 | 正常/返工新建、售后受理/调整/补发分别施工 |
| 6.5 | 9.3、9.4 | 机器端到端与用户签字分开 |
| 7.1 | 9.1 | 报表/脚本/文档/追踪同步 |
| 7.2 | 9.2 | 完整门禁先于验收铺数 |
| 7.3 | 9.5 | 最终规格核对，归档另按请求执行 |

## Requirement / Scenario 全覆盖表

按当前四份spec的**固定Requirement标题编号**追踪，不按文件出现顺序推断编号：S为scheduling-management既有S1–S25，O1–O8保留原施工引用，order-lifecycle新增的三个MODIFIED标题独立标为O9–O11并插在order相关标题之后，I为inventory-management I1–I3。不要因表格显示顺序调整既有任务正文引用。每行列出该Requirement下**全部Scenario原名**；共享用例允许参数化，但实施取证须逐场景记到具体测试方法，不能只证明测试类存在。覆盖按活动specs实际Requirement/Scenario标题核对，不写死总数；每个场景还参与9.1追踪与9.5收尾。

| 编号与Requirement | 全部Scenario（原名） | 主施工/验证任务 |
| --- | --- | --- |
| S1 排班必须分为制作排班与其他排班 | 两类同级展示 | 2.4、8.2、9.3 |
| S2 排班任务必须是多订单多产品的任务头加明细 | 多订单多产品任务；任务内重复行 | 1.3、3.4、8.3 |
| S3 明线计划与暗线事实必须分离 | 计划不产生合格；核验保留原计划 | 3.2、3.4、4.3、8.5 |
| S4 任务类型只能是 NORMAL 或 REWORK | 超额类型和端点被移除；不提供 REMAKE | 1.3、2.2、2.3、8.1 |
| S5 产品必须提供三道工序产能和标准分钟快照 | 多产品标准分钟；初始缝边只按适用数量；原实物工艺与新制造快照隔离 | 2.1、3.1、3.4、4.6、5.1、5.4、6.5、9.3 |
| S6 正常排产必须遵守日期、员工资格与产品日产能约束 | 正常产品容量不足；售后正常加工不豁免；实际修补不占正常资源；员工资格不合法 | 3.3、3.4、5.5 |
| S7 正常可安排额度必须与实际流入和历史资源分离 | 不重复扣已核验计划；三道工序不是三份客户需求 | 3.1、3.7、4.8 |
| S8 下游只能使用实际合格或兼容库存流入 | 下游等待上游；只有实际到达部分可执行 | 3.2、4.3、5.6 |
| S9 返工目标必须按当前工序与售后入口硬校验 | 缝边返工拆两个目标；装袋不得原地返工；售后缝边修补再次不合格 | 3.5、4.4、8.6 |
| S10 返工来源必须随核验完整分配并保留多轮链路 | 分配不足整批失败；同事务建来源不排任务；一源可跨任务安排；新轮不恢复旧源 | 1.4、3.6、4.4、8.4 |
| S11 修补后续加工必须恢复适用路线且不重复增加需求 | 缝边返工两条路线最终仍十件；原等待计划不能重复加额度 | 4.6、9.3 |
| S12 正常合格必须按冻结流程流转且不得相加 | 初始装袋分流；制作合格不是终点 | 4.5、5.7 |
| S13 缝边剪袋允许返工但禁止报废 | 缝边部分合格部分返工；缝边报废拒绝 | 1.3、3.5、4.3、8.6 |
| S14 报废必须保留事实并按需求从制作正常补做 | 装袋报废从制作补做；修补报废不回修补池；无补发义务只报废；后续新报废不改历史；补做多次报废承接原等待；未加工退回报废不可再安排 | 4.7、5.4、6.4、9.3 |
| S15 创建与取消必须在来源余额与事务边界内原子执行 | 创建任一来源不足全回滚；取消返工释放同源；取消不级联 | 3.4、3.6、4.2、6.3 |
| S16 未核验计划可取消且不设置开始登记 | 未来当天过期未核验计划均可取消；已实际加工但未核验也可取消整条计划；取消与核验并发互斥；不提供开始端点字段或人工前置；正完成无需开始标记且零完成仍合法；新增更早未核验计划按稳定顺序重分配；后到流入沿同序分配且核验仅消费完成 | 1.1、3.2、4.1–4.3、6.8、8.2、8.6、9.3 |
| S17 每条明细只能核验一次，批量核验必须原子提交 | 日期未过不得核验；一条超出可执行整批回滚；多明细成功；新请求重复核验拒绝 | 1.5、4.3、8.6 |
| S18 未完成数量和明细取消必须保留历史并正确派生 | 十件计划只完成六件；返工未完成不回普通需求；已核验拒绝取消 | 3.7、4.2、4.3、5.5 |
| S19 任务头状态必须由明细事实派生 | 混合状态不误判；全取消与未完成提示 | 3.7、3.8、8.2 |
| S20 制作工作量必须在提交前由服务端给出软提示 | 提交前余量；超出不阻断；排除实际修补与其他工序 | 7.1–7.3、8.3 |
| S21 售后执行必须保留所有者用途与覆盖边界 | 未排修补已覆盖；库存用途完整落地；非终点待加工实物可以转库存用途；同明细混合用途核验按份额继承 | 5.2、5.7、6.5、6.6、8.6 |
| S22 其他排班只记录工时并与制作排班同级展示 | 九十分钟其他排班；工时更正留原值 | 2.4、8.2、8.3、9.3 |
| S23 排班工作台必须以日期横向周历展示统一新建入口 | 统一新建；详情只读与批量提交；全局返工入口可取得售后归属 | 8.1–8.6、9.3、9.4 |
| S24 写命令必须具备统一错误、幂等和并发门禁 | 幂等重放；并发来源不足；同键异请求 | 3.6、4.2–4.4、6.8、7.2 |
| S25 明确不做项必须被系统拒绝或保持不变 | 客户端伪造派生量；手工来源与事实删除不存在 | 2.2、2.3、3.6、4.8 |
| O1 售后必须独立于原订单履约 | 部分发货即可创建售后；无有效已发来源被拒绝；退回等式错误；售后补发确认；售后退款；退回与补发不必相等；退回路线以原发货份额为上限；退回直接报废仍按路线守恒；补发路线由本次售后明确录入 | 5.1、5.4、5.8、8.7、8.9 |
| O9 已确认订单变化必须使用订单变更 | 减单低于已发货被拒绝；减单处理超出数量；普通增量及新增明细产生新来源；普通无实物减量终止关联额度；普通等总量路线变更不改历史实物 | 3.1、6.1、6.3、8.8、9.3 |
| O10 订单必须维护可发货和发货上限 | 超过可发货被拒绝；库存领用后发货；分批发货不得重复消费路线份额；普通作废和更正保留路线追溯 | 3.2、5.6、5.9、8.9、9.3 |
| O11 订单取消必须区分草稿和已确认 | 无事实的已确认订单取消；有事实订单直接取消被拒绝 | 3.1、6.2、9.3 |
| O2 补发批次必须与普通发货数量写入口隔离 | 普通入口不能修改或确认补发草稿；普通入口不能作废或更正已确认补发；专用确认必须验证整批售后归属；只读物流和原发货不被错误隔离 | 5.8、5.9、8.9 |
| O3 售后退回核验必须一次分配全部数量并原子生成返工来源 | 一次退回核验拆用途与目标；非适用路线或数量错误整体回滚；核验重试不重复来源 | 5.4、8.7 |
| O4 售后修补必须按当前发生工序选目标并沿适用路线继续加工 | 制作修补合格仍须装袋；售后缝边修补再次不合格；装袋修补再次不合格；售后正常加工进入统一资源汇总；制作工作量预览仅作软提示 | 3.5、4.4–4.6、5.5、7.1–7.3 |
| O5 售后来源必须按来源与目标分余额并保留任务历史 | 同源同目标拆分任务；未完成只回退原目标；未核验计划均可取消而已核验拒绝；核验日期与唯一性约束 | 3.7、4.1–4.3、5.5 |
| O6 补发覆盖必须按物理来源链只计算一次 | 正常缺口须显式授权且终止额度不复活；分次授权不得跨路线借用缺口；不同实物来源不得重复覆盖补发需求；未排返工来源已覆盖；多工序及多轮不重复覆盖；终点合格迁移为补发预留 | 5.2、5.3、5.6、6.2、6.8 |
| O7 售后报废必须结束失败覆盖并按仍需补发的缺口从制作补做 | 装袋报废从制作补做；返工报废不回旧返工余额；库存用途报废不补做；退回直接报废不免除补发承诺 | 4.7、5.2–5.4、6.6 |
| O8 补发需求调整必须保留原因历史并明确已覆盖实物去向 | 等总量换路线仍须处理减少路线覆盖；退五补五减为补三；已补四件不能改三件；减量先消减未覆盖缺口；未排无实物正常授权五减三；已排无实物正常计划五减三；无实物终止与核验竞争；有实物或未解除占用不得终止额度；未核验取消不消除退回实物；在制减量继续加工转库存；在制明细部分转库存按份额核验；用途拆分与库存报废承接既有等待计划；失效等待份额不能因取消重新安排；混合用途结果必须守恒；增加需求不自动排班 | 6.1–6.8、8.8 |
| I1 售后库存领用必须原子接入独立售后台账且不重复扣减 | 成品库存接入售后后确认补发；非终点库存还需后续加工；并发售后领用整体回滚 | 5.6、5.8、6.8、8.7 |
| I2 售后修补库存用途必须在路线终点产生可追溯入库事实 | 显式修补入库完成真实库存；修补合格但尚未到路线终点；退回未决定用途不自动入库；终点入库重试不重复增库；库存用途修补报废不产生补做 | 4.5、4.7、5.4、5.7、6.4 |
| I3 售后合格超出量转库存必须原子解除补发预留 | 五件合格保留三件补发两件入库；期初成品领用三件减需一件转新库存两件；成品领用减量转库同键重放不重复记账；成品领用减量转库失败全部回滚；成品领用取消与转库互斥；领用已有下游消费拒绝取消；完成证据不得缺失错链或冒用成品分支；转库存与补发竞争同一实物；在制转库存须继续加工；无退回补发在制品转库存保留真实来源；无实物额度终止不得虚构库存；不得转移已确认补发实物 | 5.7、6.2、6.5–6.8、8.8 |

### 文档自身检查与实施完成的区别

本清单细化完成只需检查：任务编号唯一连续；59项各有证据契约和人工证据；活动specs的Requirement和Scenario实际标题逐字覆盖；原34项映射完整；C4每端点有负责项和场景；旧18条REMOVED与基线一致；严格OpenSpec和空白校验通过。覆盖表按固定Requirement标题编号核对，表格行顺序调整不改变任务正文引用。以上仅证明清单结构/追踪可用，不证明SQL、代码、HTTP或浏览器已实现，不能据此勾选任何实施项。
