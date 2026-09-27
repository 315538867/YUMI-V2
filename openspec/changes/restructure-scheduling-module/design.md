## Context

本 change 在初次审查后按 2026-09-27 用户逐项确认更新。旧提案中的超额并表、制作单一返工池、缝边不返工、同工序报废回转与全工序工作量提示均被取代。新施工基线是 `docs/architecture/scheduling-module-design.md`，售后配套 `after-sales-module-design.md`，领域和数据库目标同步；本次仅文档，未实施。

## Goals / Non-Goals

**Goals:**

- 统一排班命名和制作/其他两类，删除超额/未来预占，只保留 NORMAL/REWORK。
- 将返工来源、目标、用途、适用路线、轮次与余额固化为服务端数量边界。
- 区分客户需求、各工序加工额度、实物流入、正常资源与售后覆盖，消除重复扣减或重复补做。
- 普通确认/ADD/增减/路线工艺变化/取消关闭同步来源生命周期；真实原发货quantity+seamQuantity和source links支撑退回上限及同份额逆向，售后不反写原订单。
- 核验原子生成目标来源；补做新physicalShareId数量级承接旧等待，实物处置失效当前及下游；非终点用途转换与两类完成证据终点入库闭环。
- 采用最小端口反转，固定scheduling→inventory→orders及scheduling→orders编译DAG、真实无环Bean接线、整批同线程同事务锁序。
- 制作工作量提交前预览仅软提示；正式周历、只读详情、批量核验、全局case选源及普通/售后操作一致。

**Non-Goals:**

- 不新增客户需求，不因售后改原订单数量、已发、未交付、应收或主状态；不改退款结清口径。
- 不做超额任务、未来预占、自动排班、工资绩效或普通核验通用编辑/冲销。
- 不做历史数据搬迁或兼容迁移；本机未上线时改写 V1，清库是未来实施动作。
- 不创建额外“返工产品当前阶段”状态子系统，不以文档更新代替测试或视觉签字。

## Decisions

1. **全栈改名**。包、表、路径、目录与编号同步为 scheduling，不只改显示。普通订单与售后仍保留各自业务所有权，共享执行不共享需求台账。
2. **删除超额而非合并**。移除三张旧超额表、任务类型/接口/预占/释放/未来调整及错误码，保留未完成提醒。原因是原需求实际是制作安排负荷提示，保留预占会继续维护不存在的业务。
3. **返工目标由当前工序决定**。制作→制作，装袋→制作，缝边→制作/装袋；售后退回可三种适用目标。目标可按数量拆分，核验时必须分完、同时生成来源，不再手工补建，不自动任务。缝边报废恒零，完成=合格+返工。
4. **来源所有权与目标双维**。来源保存原核验/退回入口、订单或售后、用途、目标、数量、路线和轮次。允许一来源分多任务，使用分配事实与锁定余额而非全局来源唯一安排。新轮消费新来源，不回补旧轮处理量。
5. **实际修补才 REWORK**。修补后的适用后续段为 NORMAL，含售后计正常产能与工时。已处理需重过的段生成加工额度；未处理原额度/等待计划只承接实际流入，避免重加需求。缝边路线随源保留，不能以历史累计缝边量决定返工件免缝边。
6. **报废从制作补做**。废件不是原工序流入。仍需交付的加工报废生成MAKING NORMAL补做、新physicalShareId及replacesPhysicalShareId/quantity/scrapRecordId/前后allocation映射，按数量承接旧份额尚未处理的source/task等待，原ID/计划/资源不改、额度不重复，补做合格才流入；已处理段另建重过额度。旧失败覆盖退出、新份额承接一次，反复补做不复活旧件。库存用途/已取消需求不补做；直接退回报废不免承诺，缺口显式正常授权（C7，4.7、5.3）。
7. **安排余额与历史资源分离**。加工来源完整余额=total−terminated−PENDING−processed−invalidatedUnoccupied；SHIPPABLE成品锚点不适用此式，按决定18独立派生terminalAvailableQuantity。加工来源不重复扣已核验计划或PENDING失效。一次执行登记及C9真实流入保护均仍待1.1：开始时绑定已有真实流入，后建更早计划不抢占，后到流入先按开始时间/ID补已开始剩余，再按日期/ID分未开始；不绑定未来流入。核验次日一次完成，消费完成、释放有效未完成绑定；失效不回可排，历史资源保留。仅未核验无执行标记计划可取消，不级联；未经评审不得实施这些执行字段/守卫。
8. **售后覆盖按实物链只计一次**。补发总量仅缺省默认退回，显式0保留；returnedSeamQuantity与replacementSeamQuantity分别必传、完全独立，按各自总量校验。受理锁原发货明细，退回路线量不超其有效source links扣除其他有效退回占用后的真实份额，保存原工艺引用；总受理上限和次数规则不变，不把退回+补发相加。VerifyReturnRequest显式提交实际returnedQuantity/returnedSeamQuantity，满足0≤seam≤total≤acceptedQuantity；核验同事务按L重锁原发货及售后明细，按有效原发货份额扣除其他有效退回占用重验实际两桶，追加受理预录与实退前后差异、原子替换本明细未核验路线占用并保留旧引用历史，不改变他人占用或独立补发承诺，核验后禁止覆盖。existing correct只留审计，不是未核验改量生产者，不要求实际等于预录或先更正。各路线修补不超实际退回，差额为各路线报废且合计等于scrapQuantity（C3）。补发用途未排已覆盖，来源/在制/预留/已补互斥迁移一次，终点库存真实入库，只有确认增加已补（C8，5.1/5.4）。
9. **补发调整显式处置**。增加仅出缺口，减少先未覆盖；无实物未执行授权追加终止并显式合法取消全链相关计划，不造报废/库存。CONTINUE_TO_INVENTORY覆盖退回/库存接入/前段合格全部非终点有效份额，无任务、等待未开始或已开始均可，保留阶段和路线、传播等待用途映射、覆盖解除一次，不提前入库/伪造start；已被下游消费须选当前有效后继。SCRAP_RETURNED仅未开始退回合法报废，解除占用后失效当前source及全部未处理等待份额，processed/terminated不冒充处置。终点合格转库按决定19验证证据，不能低于已补发、不覆盖历史（C7，6.1–6.7）。
10. **制作服务端工作量预览**。仅员工+日期 MAKING NORMAL，含草稿，与工作日小时×60×有效率比较；不计 REWORK/其他，不扩到其他工序。BigDecimal 精确比较、显示舍入不影响判断，预览/创建/详情/工作台共用计算入口。配置版本随响应返回，历史标准分钟快照不回写。
11. **统一周历而非合并所有表**。其他排班独立分钟表保留，与制作卡片同级日期列展示。一个新建入口，来源/额度就近展示，详情只读；核验页按明细分目标，一次写请求。
12. **单基线与整批同事务事实**。V1目标另行授权实施。同步同线程外层业务事务+内部写端口/协调器/ledger/Receipt/writer MANDATORY，禁止afterCommit、async、REQUIRES_NEW、内部HTTP。整批inspect引用，再owner（受理先原发货明细）→task→source/share/coverage→capacity→inventory→稳定序列锁，各层整批稳定排序，禁止每行独立走L后返回owner。所有普通变更行（ADD、无实物增减、路线/工艺变化、实物/取消处置）进入OrderSchedulingDispositionPort的一份Influence/LockedPlan；整单inspect收齐既有引用→owner锁→lockAndValidate其余L锁→orders写变更应用结果→applyLocked同时建新源及处置旧份额。禁止沿逐行apply循环各走L，不调用生命周期变更旁路。确认ADD由orders同事务持久化order_change_item_applications(order_change_item_id UNIQUE,created_order_item_id,route_snapshot_id)，UPDATE/REMOVE仍用原orderItemId，适配器按真实映射读取新明细/快照；同商品双ADD不能按商品、行号或查询顺序猜。新行无需锁尚不存在资源，既有资源全纳入锁计划。普通/售后各自dispositions以inspect/lockAndValidate/applyLocked三段同事务执行，底层取消只用已持锁核心，不重入外层取消；引用变化须重验，不偷偷反序补锁（tasks L，1.2、2.1、4.3、6.3/6.8）。
13. **补发写入口隔离**。补发身份与关联在草稿创建时原子固化，不与普通明细混批；普通发货草稿修改、确认、作废、等量更正识别补发并返回STATE_NOT_EDITABLE，不进入普通履约台账。售后专用确认锁内校验整批归属；当前不提供补发草稿改量、作废或等量更正，通用售后更正也不能绕过。只读和合法物流操作可复用，但不改数量、身份、关联或履约状态。

14. **补发路线显式录入（2026-09-27用户确认）**。受理明确补发总量及其中缝边量，冻结新正常制造合法工艺/分钟，不能复制整单缝边量或比例猜测。需求/覆盖/预留/已补/gap按有缝无缝分桶，授权quantity/seamQuantity分别不超gap；调整逐路线守已补下限，等总量换路线仍处理减少并只为增加出缺口。实物/用途份额routeSnapshotId冻结具体工艺，退回修补及重过继承原发货快照，新正常制造使用新冻结快照，两者不能互相覆盖；同有缝boolean不表示A/B工艺兼容，不新增改造许可（C7，3.1、4.6、5.1/5.4）。
15. **用途份额承接既有等待计划**。初始建源即落非空用途/路线份额，稳定实物份额标识贯穿各段。用途调整在同事务向选中份额尚未处理的既有等待来源/明细追加替代映射，不改计划或历史资源、不重复增额度；不得改已消费历史。库存份额上游报废不补做，其未处理下游额度追加幂等失效事实，区别于无实物终止；等待原计划保持，可执行剔除失效，后续核验仍记录原计划减完成的未完成量，但失效未完成不回可排、不提醒重排，取消也不复活。明确算法及5→3/1/1整链验收见tasks C7。

16. **普通生命周期不能遗漏源写入**。确认/ADD同事务冻结各段来源，增加仅新起因授权、不改旧total；减少按路线/工艺处理无实物终止或真实份额FINISH_TO_SURPLUS/SCRAP，并显式取消相关合法计划。等总量换路线也一减一增，需求逐路线不低有效已发，旧实物/任务不改工艺。普通处置以orderChangeItemId区别售后afterSalesDispositionId，不借售后FK；实物沿前沿去重，不能叠加工序流入，合法报废失效当前/下游、不为已取消需求补做。无业务/款项事实的确认订单取消同事务终止初始授权；取消/关闭拒新ORDER_DELIVERY源/计划，不误禁合法INVENTORY链或独立售后；关闭仍守履约/结清条件（C8，3.1及4/6组回补）。
17. **原发货消费与逆向同份额**。普通草稿每行quantity+seamQuantity，确认按稳定兼容可发份额ID消费并保存真实shipment_source_links及routeSnapshotId/sourceLineId，不填0、不逐批重分原始IN、不猜整单路线、不二扣库存。void按原links追加逆向恢复同份额可发，不恢复库存；已关闭等量correction在既有条件下原子转同份额至替代批次，失败原批次有效、重放不双释放，仍拒有效售后占用（C8，3.2/4.5、8.9）。
18. **库存接入与既有逆向闭环**。期初生产入口同时扩展OpeningRequest、库存批次及frontend/src/pages/inventory/InventoryPage.tsx：明确真实routeSeamRequired，有缝选合法seamTypeId，inventory冻结自有不可变路线/工艺/分钟证据（不依赖scheduling DTO），node/seamState校验完成阶段。单批单工艺/路线，混合分批、不接收客户端标准分钟；库存增量调整继承原批证据。领用由scheduling适配器读取并生成/关联排班routeSnapshotId，不要求期初有核验、不以当前订单工艺回写批次（3.2、8.7）。所有成品直接领用也同事务生成真实INVENTORY_INFLOW根/source、physicalShare及purposeAllocation，targetNode=SHIPPABLE仅作追溯；不以inventoryAllocationLineId冒充sourceId、不增加加工额度或任务，total仅记原接入量，可排/可执行恒0。terminalAvailableQuantity独立由有效终点流入减发货/转库/逆向派生，非终点该值为0；售后根直接RESERVED一次，普通直接可发。SourceView.targetNode及orders查询DTO扩为Node | 'SHIPPABLE'，purposeShares返回terminalAvailableQuantity、completionEvidenceType及真实terminalVerificationId/inventoryAllocationLineId/inventoryMovementLineId，按C5分支互斥给证据。GET售后case、scheduling-sources和普通fulfillment真实返回source/root/allocation及上述余额/证据，减量3→1使用返回ID处置2；创建表单过滤SHIPPABLE且服务端拒建任务，不新增顶级来源页。普通领用同步终止跳过工序授权并接所需等待，不保留同需求两份额度；显式合法解除受影响计划。保留existing reverseMovement及普通合法取消，锁内检查全部source/flow/coverage；无开始/核验/发货/转库等消费才可一次逆向。普通仍缺量用新取消起因授权，不复活旧ID/减历史terminated；售后不能误走普通回库，不新增售后取消HTTP（C8，3.2、5.6）。取消/冲销仍只传reason、计划事先显式解除，cancel/reverse按同一起因去重；排班终点或需求处置产生的入库不得孤立reverse，返回STATE_CANNOT_CANCEL，独立期初/库存调整合法冲销保持（C5）。
19. **Receipt两类完成证据**。加工转库必须TERMINAL_VERIFICATION+真实terminalVerificationId；成品直接领用终点RESERVED减量转库用FINISHED_INVENTORY_ALLOCATION+真实batch/allocation、inventoryAllocationLineId、inventoryMovementLineId、physicalShareId/路线，不伪造核验，非终点不能冒用。证据互斥且不可全空，与调整处置origin分离，幂等仍originType+originId+originLineId。普通/售后转库均新批次/流水，不恢复旧批次、不冲原出库、不二扣；需求/用途/覆盖/库存任一失败全回滚，已有消费拒整笔领用逆向（C5，5.7、6.7）。普通用inventory自有OrderSurplusReceiptService/OrderReceiptRequest，售后用AfterSalesInventoryReceiptService/ReceiptRequest，共用底层核心而不混FK；售后领用扩展既有inventory_allocations/inventory_allocation_lines头/行的owner_type及互斥订单/售后归属，真实落movementLine与份额，普通/售后及非终点/终点共用，不新建影子表或伪领用行，不能假设旧实现已有售后allocationLine（1.2/1.4、3.2/5.6）。
20. **最小端口反转落到类型与Bean图**。编译DAG scheduling→inventory→orders且scheduling→orders，禁止orders→scheduling/inventory、inventory→scheduling。orders拥有port包及NamedInterface/DTO：OrderSchedulingSourceQueryPort、OrderSchedulingLifecyclePort（仅initializeOrder/cancelOrder/verifyOrderClosure/applyReturnVerification，即确认/取消/关闭/退回；删除applyOrderChange）、OrderSchedulingDispositionPort（所有普通变更）、AfterSalesSchedulingDispositionPort；inventory拥有InventorySchedulingPort。实现放scheduling/integration/{orders,inventory} Adapter；FulfillmentService只调orders自有query返回orders SchedulingSourceView、字段映射C2，不引用scheduling类型。参数/返回/泛型/嵌套枚举等签名闭包不得带实现模块类型。orders ledger/coverage仍orders所有且只依赖repo，inventory Receipt/InventoryAllocationWriter仍inventory所有由scheduling调用，普通与售后转库都经适配器，orders不直接调inventory。InventoryService薄入口拿batch锁前进入协调器；Receipt/writer不可回调InventoryService/反向port，Adapter不可回调OrderService/AfterSalesService/FulfillmentService。禁止Lazy、allow-circular、OPEN、shared业务DTO掩盖循环（tasks C10目标，2.1/2.2）。
21. **真实接线门禁与全局归属**。ModuleStructureTest将production预期改scheduling并断言DAG+NamedInterface/签名；YumiApplicationTest真实全上下文禁lazy/循环、每port唯一真实Adapter，失败注入证明全链原子。前移2.1/2.2、9.2回归，不以mock代替、不在本轮运行。所有来源含全局返工返回afterSalesCaseId：ORDER售后两ID均null，AFTER_SALES两ID非空匹配；统一新建不靠导航猜case，拒普通/售后混选和跨case（C2，3.8、4.4、5.3、8.4）。

## Risks / Trade-offs

- **来源链比总量投影复杂**：增加明确承接/重过工序事实，但避免“一次修补多次覆盖”和缝边误分流。售后已开始明细部分转库存以追加用途份额表达，核验引用份额分别记录结果/返工目标，组守恒并继承用途，库存组报废不补做；原计划资源不改。终止后的新增正常缺口通过POST售后scheduling-sources显式新授权，不复活旧额度、不自动任务。固定10件缝边8合格2返工、未加工等待计划、多轮、报废补做及混合用途5拆3/2算例。
- **跨模块范围扩大**：本次不再是只限制售后目标，涉及退回、覆盖、库存和减量处置。把 order-lifecycle/inventory-management 纳入 delta 与独立实施任务，不沿用“售后不受影响”。
- **锁顺序冲突**：现有创建、核验、售后和库存路径不可各自保留相反顺序，先列物理锁行，再测并发创建、分配、核验、调整、入库和幂等重放。
- **工作量不是产能放行**：超出工时可提交不代表绕过来源或产品日产能，前后端分别展示软提示和硬错误。
- **清库与验收数据冲突**：实施先核实本机库边界，完整门禁后重建验收数据再浏览器验收；不在铺数后再跑会改库的测试。
- **旧名残留与历史文档**：实现时扫描脚本、报表、错误码、类型、路由和追踪表；历史归档保留原文并清楚标注，不把旧证据改成新完成。

## Engineering Follow-ups

此前逐项确认的返工、报废、覆盖与工作量规则保持；一次执行登记与C9“开始后绑定真实流入、较早新计划不抢占、后到流入优先补已开始剩余”一起仍待tasks 1.1用户明确评审。此次仅文档修复授权不是批准，不代签、不先实施相关字段/守卫；否决时须同步契约、规格和测试，不擅自选替代判据。未新增返工阶段状态子系统。

最小端口反转研究结论已明确，2.1/2.2按决定20–21及施工文档§10/§11落实DAG、接口拥有者、锁序与真实上下文门禁，不再留为泛化“公开接口”。1.2按C1/C7/C8落定来源/终止/失效/补做替代/发货正逆向/两类Receipt证据的列、FK、CHECK、业务键和物理锁行；编号目标ST+6位、scheduling_tasks序列键仍需检查空间。C2/C3/C5/C7/C8/C9/C10为本轮同步依据，包含ADD应用映射、期初库存快照生产与SHIPPABLE锚点独立余额；未来验收补mixedOrderChangeUsesOneLockPlan、twoSameProductAddsKeepDistinctOriginMappings、finishedInventoryRootIsQueryableButNotSchedulable、finishedAllocationThreeToOneUsesReturnedSourceIds，以及实际退回不同于预录的两桶/占用替代/差异审计、补发承诺不变和失败回滚，不能用更正审计冒充改量生产者。3.1/3.2生产普通来源与原发货，4/5/6组回补整链，9组留真实测试和人工证据。本轮不改代码/数据库、不运行应用测试、不提交；任务全部未完成，视觉证据保持 `pending-user-signoff`。
