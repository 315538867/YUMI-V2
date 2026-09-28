## Why

现有生产模块把管理员所需的制作工作量提示误建为超额任务及未来预占；返工目标、报废补做、后续加工额度与售后覆盖也未形成一致数量边界。2026-09-27 逐项确认：排班分制作排班与其他排班，商品任务只有正常加工和实际修补；返工须记录来源与目标，报废需交付则从制作重做，售后独立记录用途和补发覆盖。本 change 据此修正原提案，不继续“超额并表、单一制作返工池、同工序报废回转”的旧方向。

当前为文档/实施目标契约，所有实现与验收仍未完成。2026-09-27用户已明确无开始任务/开工登记，未核验计划不论日期或现场加工均可取消，已核验不可取消；该决定替代此前未获批准的执行登记及C9保护候选，1.1改为已明确规则的文档/实施契约核对，实施与视觉签字仍未完成，不实施代码/数据库、不运行应用测试、不提交、不代签。模块研究结论已明确采用最小端口反转，以下为精确施工目标而非已落地事实。

## What Changes

- **BREAKING** `production` → `scheduling` 全栈改名：表、包、跨模块接口、前端目录、正式路由、编号前缀及文案同步。
- **BREAKING** 删除超额任务、未来预占及调整机制：移除 `overtime_tasks`、`overtime_task_items`、`overtime_preemptions` 及服务/API/提醒/错误码，不创建改名预占表，不向任务增加 `OVERTIME`；任务类型仅 `NORMAL` / `REWORK`，继续不支持 `REMAKE`。
- **BREAKING** 返工核验一次按目标分完数量，与来源同事务生成，不再手工补建来源；制作可返制作，装袋返制作，缝边可返制作或装袋；售后退回可选择三道适用工序。缝边仍禁止报废，但允许返工。
- 仅实际修补段为 `REWORK`；之后顺序经过适用的 `NORMAL` 工序并计正常资源。保留来源、目标、用途、路线与轮次，支持一源多任务；重过已处理工序增加加工额度，不增加客户需求、不重复给尚未加工的等待计划额度。
- **BREAKING** 需交付的加工报废从制作形成NORMAL补做，新physicalShareId通过replacesPhysicalShareId、quantity、scrapRecordId及allocation映射承接原未处理等待source/task，保留原计划/资源、不重复增额度，合格才流入；反复补做不复活旧件。库存用途/已取消需求不补做，直接退回报废不免补发承诺，缺口显式授权（C7，4.7）。
- 区分初始需求、PENDING、processed、terminated、invalidatedUnoccupied与实物流入/历史资源，失效不与PENDING双扣；仅有效未完成退同源，失效不可重排/取消复活。C9所有未核验计划按同兼容池taskDate/itemId稳定分配，读与核验锁内同算法，核验消费完成并释放有效未完成；不绑定未来流入、不增加额度/覆盖；首道MAKING无实物正常授权仍按合法授权余额执行，不强套下游实物流入限制。核验仍任务日期之后、一次、可完成0，正完成无开始前置；未核验不论未来/当天/过期或现场加工均可取消，释放本计划有效来源占用及NORMAL资源、不抹流入、不级联，已核验拒绝，取消/核验互斥（3.2、4.1–4.3）。
- 普通确认/ADD同事务建冻结来源，增加仅新起因授权，减少/等总量路线工艺变化须逐路线处置和显式合法取消，旧total/任务/实物快照不改；取消关闭同步来源守卫，合法库存余量及独立售后不误禁。普通处置用orderChangeItemId，不借售后case或demandAdjustmentId；3.1先无实物，4/6组回补实物闭环（C8）。
- **BREAKING** 普通发货草稿逐行quantity+seamQuantity，确认按真实可发份额保存shipment_source_links/routeSnapshotId，不从整单缝边量或原始IN反复猜分。void只反向同份额可发、不恢复库存；已关闭等量correction原子传同来源/路线至替代批次，失败保持原有效，重放不双释放（3.2、4.5、8.9）。
- 售后补发批次与普通发货写入口隔离：普通发货接口不得改数量、确认、作废或更正补发批次，补发只经售后专用命令处理；不影响普通只读和不改变履约数量的物流操作。
- 制作工作量仅面向管理员、员工 + 日期的 MAKING NORMAL 工时，与 `workday_hours × 60 × making_effective_hour_rate` 比较。新增提交前服务端预览，余量/超出均为软提示，不扩展到装袋/缝边，不产生未来预占。
- **BREAKING** 退回/补发总量独立，补发仅缺省默认退回量、显式0保留；returnedSeamQuantity与replacementSeamQuantity分别必传，退回每路线受原发货有效source links扣其他退回占用后真实份额约束，不把退回+补发相加限制受理。VerifyReturnRequest显式提交实际returnedQuantity/returnedSeamQuantity（0≤seam≤total≤acceptedQuantity），核验同事务锁原发货及售后明细，扣除其他有效退回占用后重验实际两桶，记录预录到实退差异、原子替换本明细未核验路线占用并保留旧引用；不动他人占用及独立补发承诺，核验后不可覆盖。existing correct只审计，不提供未核验改量，不能要求等于预录或先更正。每路线修补不超实退、差额为报废，合计等于scrapQuantity（C3）。routeSnapshotId冻结具体工艺：退回及修补/重过沿原实物，新正常制造用受理冻结快照，同为有缝不等于工艺兼容（C7/C8，5.1/5.4）。
- 补发覆盖按物理链互斥计一次，含未排来源。确实无实物且未发生加工的授权才可终止并显式合法取消全链相关计划；未核验或计划取消不等于无实物/未加工，有实物继续原合法处置。CONTINUE_TO_INVENTORY支持非终点无任务/待加工/在制实物，传播等待用途映射、解除覆盖一次、不提前入库；SCRAP_RETURNED仍仅限未实际加工退回实物且占用已显式解除，失效当前及全部未处理等待份额，不能冒充processed/termination。终点超出真实转库，调整守逐路线已补下限（C7，6.1–6.7）。
- 库存工艺证据从期初生产：扩展OpeningRequest、库存批次及frontend/src/pages/inventory/InventoryPage.tsx，录入真实routeSeamRequired和有缝时合法seamTypeId，inventory冻结自有不可变工艺/分钟快照、校验node/seamState；单批单路线/工艺、混合分批，不收客户端分钟，库存增量调整继承原批。scheduling适配器在领用时读取并生成/关联routeSnapshotId，不要求期初有排班核验、不用当前订单工艺回写库存、不泄漏scheduling DTO（C5，3.2、8.7）。
- 成品直接领用也真实生成INVENTORY_INFLOW root/source、physicalShare及purposeAllocation；targetNode=SHIPPABLE仅作追溯，不增加加工额度或任务，total仅记原接入量，可排/可执行恒0，inventoryAllocationLineId不能冒充sourceId。terminalAvailableQuantity独立由有效终点流入减发货/转库/逆向派生、不套加工balance公式，非终点为0；售后直接RESERVED一次、普通直接可发。SourceView.targetNode及orders查询DTO同步Node | 'SHIPPABLE'，purposeShares返回terminalAvailableQuantity、completionEvidenceType及terminalVerificationId/inventoryAllocationLineId/inventoryMovementLineId真实分支证据。GET售后case/scheduling-sources及普通fulfillment返回真实source/root/allocation和余额/证据，3→1减量使用返回ID处置2；创建表单过滤终点、服务端同样拒建任务，不新增顶级来源页（C2/C5）。
- Receipt区分TERMINAL_VERIFICATION+真实terminalVerificationId与FINISHED_INVENTORY_ALLOCATION+真实batch/allocation/领用出库行/physicalShare/路线，两者互斥且不可全空；成品领用减量可无虚构核验、非终点不得冒用。转库新建批次/流水、origin幂等，不恢复旧批次/冲原出库/二扣库存。保留existing reverseMovement并同步source/flow/coverage，任一份额已真实加工或被核验/下游/发货/转库等消费拒整笔逆向；计划未核验或已取消不豁免，不依赖执行字段、不增人工确认步骤，cancel/reverse同起因去重；核验/处置产生的入库不可孤立冲销，独立期初/库存调整合法冲销保留，不新增售后取消HTTP。普通/售后Receipt分开所有者FK共用inventory核心；普通/售后及非终点/终点共用既有inventory_allocations/inventory_allocation_lines，扩展owner_type及互斥订单/售后归属，在领用事务真实写头/行、原出库movementLine及路线份额，不新建影子表、不用假行补证据（C5/C8，1.2/1.4、3.2、5.6–5.7、6.7）。
- 全局来源含afterSalesCaseId，ORDER两售后ID均null、AFTER_SALES非空匹配，统一新建不靠导航猜case，拒普通/售后混选或跨case（C2，3.8、4.4、5.3、8.4）。
- 最小端口反转固定编译DAG scheduling→inventory→orders且scheduling→orders，禁止orders→scheduling/inventory、inventory→scheduling。orders拥有NamedInterface port包/DTO及OrderSchedulingSourceQueryPort、OrderSchedulingLifecyclePort（仅initializeOrder/cancelOrder/verifyOrderClosure/applyReturnVerification，即确认/取消/关闭/退回；删除applyOrderChange）、OrderSchedulingDispositionPort（所有普通变更）、AfterSalesSchedulingDispositionPort；inventory拥有InventorySchedulingPort；scheduling/integration/{orders,inventory} Adapter实现。FulfillmentService调orders自有query返回orders SchedulingSourceView映射C2，所有端口参数/返回/嵌套枚举不得泄漏实现类型（2.1/2.2，C10目标）。
- orders ledger/coverage只依赖repo；inventory拥有Receipt和InventoryAllocationWriter，由scheduling调用，普通/售后转库都经适配器，orders不直调inventory。InventoryService薄入口在batch锁前进入协调器；writer/Receipt不回调InventoryService或反向port，Adapter不回调OrderService/AfterSalesService/FulfillmentService；禁Lazy、allow-circular、OPEN、shared业务DTO掩盖循环。
- 同线程外层事务+内部MANDATORY，禁止afterCommit/async/REQUIRES_NEW/内部HTTP。整批inspect→owner（受理先原发货明细）→task→source/share/coverage→capacity→inventory→稳定序列锁，不逐行走L；普通全单所有ADD、无实物增减、路线/工艺变化及实物/取消处置走OrderSchedulingDispositionPort一份Influence/LockedPlan，整单inspect→owner锁→lockAndValidate其余L锁→orders写变更应用结果→applyLocked同时建源及处置，不能沿旧逐行apply循环各走L或调用生命周期变更旁路。orders确认ADD同事务持久化order_change_item_applications(order_change_item_id UNIQUE,created_order_item_id,route_snapshot_id)，UPDATE/REMOVE仍用原orderItemId，适配器按真实映射读新明细/快照；同商品双ADD不按商品、行号或查询顺序猜。新行不补锁尚不存在资源，已有资源先纳入锁计划。普通/售后各自dispositions的inspect/lockAndValidate/applyLocked不跨事务，底层取消不重入外层。ModuleStructureTest改production为scheduling并断言DAG+签名，YumiApplicationTest真实全上下文禁lazy/循环、唯一真实端口，失败注入证明原子，门禁前移2.1/2.2并在9.2回归；本轮不运行。
- 其他排班保留独立分钟事实，与制作排班进入日期横向周历；统一新建、只读详情、一次请求批量核验和来源追溯。
- 实施时重写本机单一 V1 基线并清库重建，不做兼容迁移。此次文档更新不执行该操作。

## Capabilities

### New Capabilities

- `scheduling-management`: 统一任务与核验、分目标返工、来源数量守恒、后续正常加工、制作报废补做、制作工作量提示、独立其他排班、周历与事实追溯。

### Modified Capabilities

- `production-management`: 移除原 18 条要求，逐条映射到新排班能力；超额预占明确删除，不迁移为新功能。
- `order-lifecycle`: 普通确认/ADD/增减/路线工艺变更/取消关闭的来源生命周期，原发货真实路线消费及同份额void/correction；售后退回原份额上限、独立补发路线、覆盖、调整去向与退款隔离。
- `inventory-management`: 普通/售后接入同步来源与覆盖，existing reverseMovement守卫及逆向；非终点用途转换继续加工、两类完成证据的合格转库实际生成可追溯批次/流水，同事务解除对应覆盖/预留而不二扣库存。

## Impact

- 数据：`backend/src/main/resources/db/migration/V1__yumi_v2_schema.sql` 的目标改名、删超额、来源/目标/用途/路线/分配/覆盖约束；后续空库验证，不搬迁历史数据。
- 后端：scheduling 与 orders 履约/售后、inventory、catalog、calculation、reporting 接口、锁序、错误码和投影重建均需联动；售后 NORMAL 不再绕过正常产品产能。
- 前端：正式 `/scheduling*` 与订单售后区域；返工目标拆量、来源/用途解释、制作提交前预览、需求减少处置与库存结果。
- 文档：更新排班、售后、领域、数据库设计及当前 change；旧生产施工文档标为历史。实现阶段同步追踪表、铺数脚本、运维/报表引用和验收报告，不把历史证据改写为新功能已完成。
- 承接归档 change 的工作台、详情/批量核验和浏览器验收移交项；新路由取证及用户签字前不勾选。
