## ADDED Requirements

### Requirement: 售后库存领用必须原子接入独立售后台账且不重复扣减

系统 SHALL 在同一事务锁定兼容库存批次、校验余额、生成出库流水并接入独立售后数量台账；每笔实物只能接入一次，不能同时计库存来源与修补/正常加工合格来源。新增库存补发接入 SHALL 不超过同事务重算的未覆盖补发缺口，不得与其他实物来源重复覆盖同一承诺。完成售后适用路线的库存 SHALL 可接入售后可补发，尚需后续工序的兼容库存 SHALL 仅接入对应阶段并经 `NORMAL` 完成适用后续路线；不得伪造原订单工序流入。补发确认 SHALL 只消耗售后可补发，不再次扣减原库存，也不改变原订单订购、已发、未交付、应收或主状态。原有不可变流水、余额重建及冲销更正规则 SHALL 继续适用。

普通/售后、非终点/终点领用 SHALL 扩展同一套既有 `inventory_allocations` / `inventory_allocation_lines`，保存 owner_type 及互斥订单/售后归属，在扣库同事务真实生成 head、line、原出库 movementLine 与批次/路线份额关联。现有售后仅 movement line 不构成 allocation line 证据，不得虚构 FK 占位行或另建售后影子表；既有 HTTP 响应形状保留，真实 ID 必须可追溯。

期初 `OpeningRequest` / `InventoryPage` SHALL 显式输入 routeSeamRequired，正缝边选择合法 seamTypeId，由 inventory 服务端冻结本域不可变路线、工艺/版本及标准分钟，node/seamState 校验完成阶段；单批单路线/工艺，混合分批，不接受客户端分钟，不从目标订单或当前配置补猜。库存增量调整 SHALL 继承原批证据。scheduling adapter SHALL 验证库存证据及兼容性后生成/关联排班 routeSnapshotId，inventory 不得引用排班 DTO，也不得要求期初有不存在的终点核验。

所有成品直接领用 SHALL 同事务生成真实 `INVENTORY_INFLOW` root/source、physicalShare、初始 purposeAllocation 和终点流入，普通直接可发，售后直接 RESERVED 且只覆盖一次。`targetNode=SHIPPABLE` 仅为终点追溯锚点，不属于任务加工 Node；total 只记录原接入量，balance/executableQuantity 恒0，无加工额度/任务/核验。份额 terminalAvailableQuantity SHALL 独立从有效真实终点流入扣有效发货/补发、转库及接入逆向派生，普通发货作废反向原消费、更正传递同份额，非终点该值为0，不套加工余额公式。普通 fulfillment、售后 case/scheduling-sources GET SHALL 返回真实 source/root、allocationId/physicalShareId/routeSnapshotId、终点可处置量及实际完成证据引用，不以 inventoryAllocationLineId 冒充 sourceId，不在 GET 补建。前端建任务过滤 SHIPPABLE，服务端也拒绝其建任务，减量转库使用 GET 返回的真实 sourceId/allocationId。

领用 SHALL 显式提交quantity及其中seamQuantity，分别匹配冻结补发路线、批次兼容性和对应路线gap；不得用总缺口掩盖某一路线超额。入账 SHALL 保存实际用途/路线份额；补发草稿记录各行总量及缝边量，专用确认只消费对应路线的真实预留并分别增加已补发，不二扣原库存。

已完成适用路线的成品库存直接进入售后 `RESERVED` 时，完成证据 SHALL 为 `completionEvidenceType = FINISHED_INVENTORY_ALLOCATION`，追溯真实 `batchId`、领用 allocation 及 `inventoryAllocationLineId`、原领用出库 `inventoryMovementLineId`、`physicalShareId` 与实际路线份额；不得为期初或其他合法成品库存虚构终点任务核验。尚需后续加工的库存 SHALL 不得使用此证据直接形成合格预留或合格转库，必须在适用加工终点取得 `TERMINAL_VERIFICATION` 证据。

#### Scenario: 成品库存接入售后后确认补发
- **WHEN** 管理员为合法售后明细领用 3 件兼容且已完成适用路线的成品库存，再确认 3 件补发
- **THEN** 领用原子扣减库存并增加 3 件售后可补发，真实领用头/行/原出库及 INVENTORY_INFLOW root/source、physicalShare、purposeAllocation 同时存在；GET 返回 SHIPPABLE 锚点、真实 sourceId/allocationId 和成品证据，balance/executable=0，terminalAvailableQuantity 从3在确认补发后变0，不能用该锚点建任务或伪造核验；补发确认增加已补发，原库存不出现第二次扣减且原订单数量台账不变

#### Scenario: 非终点库存还需后续加工
- **WHEN** 兼容领用库存仅完成制作而售后路线仍需装袋及缝边
- **THEN** 系统仅形成售后阶段接入和同一实物覆盖，后续用 `NORMAL` 计正常资源，适用路线终点之前不增加可补发

#### Scenario: 并发售后领用整体回滚
- **WHEN** 两个售后领用事务竞争同一批次且余额只够一个
- **THEN** 仅一个事务成功，另一个返回 `STOCK_INSUFFICIENT` 并整体回滚，不留下负库存、部分售后接入或重复覆盖

### Requirement: 售后修补库存用途必须在路线终点产生可追溯入库事实

系统 SHALL 保留显式库存用途决策：退回核验可将修补量选择为库存用途，补发需求减少也可将对应未排或在制实物明确转为库存用途；不得把退回本身自动变库存或恢复原发货库存。修补用途与目标工序 SHALL 独立，库存用途可从适用的 `MAKING`、`PACKING_BAG` 或 `SEAM_CUTTING` 开始实际修补。只有实际修补为 `REWORK`，合格后所有适用后续段 SHALL 以 `NORMAL` 顺序完成并计正常产能与工时。系统 SHALL 在适用路线终点形成可追溯库存批次及不可变入库流水，不能只停留在待办提示；加工来源的入库事实 SHALL 使用 `completionEvidenceType = TERMINAL_VERIFICATION`，关联售后明细、有效发货、实际加工来源、用途决定及真实 `terminalVerificationId`，并匹配相应实物份额、路线和合格数量；存在退回/返工时另关联退回核验、目标分配和适用的轮次父源，无退回补发的NORMAL在制品转库存则追溯正常来源与 `demandAdjustmentId`，不得伪造退回核验或返工父源，也不得省略真实终点任务核验。成品库存直接领用后的合格超出转库适用下述 `FINISHED_INVENTORY_ALLOCATION` 分支，不能将该例外泛化为所有加工入库的核验均可空。该数量 SHALL 不计售后可补发、不计已补发、不再占补发覆盖、不增加原客户需求；入库事实 SHALL 幂等且可由流水重建。库存批次仍不得绑定目标订单，售后链仅作为来源追溯。

#### Scenario: 显式修补入库完成真实库存
- **WHEN** 管理员将 2 件退回物分配为制作修补用于库存，并在修补合格后完成适用装袋及缝边正常加工
- **THEN** 系统在路线终点生成关联完整售后链的库存批次和 2 件入库流水，不增加可补发或已补发，不恢复原发货扣减的批次

#### Scenario: 修补合格但尚未到路线终点
- **WHEN** 库存用途实物完成制作修补，但适用装袋或缝边尚未完成
- **THEN** 系统只流转到适用下一工序，不直接生成可用库存或可补发，不能用待办提示宣称已入库

#### Scenario: 退回未决定用途不自动入库
- **WHEN** 系统收到客户退回记录但尚无有效库存用途决定及路线终点合格事实
- **THEN** 库存批次余额与原发货库存均不变，不自动将退回总量恢复为库存

#### Scenario: 终点入库重试不重复增库
- **WHEN** 同一库存用途来源链的终点核验或入库请求以相同幂等键重试
- **THEN** 系统返回原结果，仅存在一次该终点合格数量对应的批次/入库事实，不重复累计库存或售后覆盖；该核验产生的入库以及需求处置入库均不得经通用 reverseMovement 孤立冲销，返回 STATE_CANNOT_CANCEL 且保留原链，独立期初/库存调整的既有合法冲销仍可用，不新增核验/处置逆向

#### Scenario: 库存用途修补报废不产生补做
- **WHEN** 无需补发或已转库存用途的退回实物在允许报废的修补/后续工序报废
- **THEN** 系统保留发生工序、来源、原因与报废明细，不生成库存入库、补发覆盖或补做额度，也不退回原返工可修补余额

### Requirement: 售后合格超出量转库存必须原子解除补发预留

系统 SHALL 在显式转库存或补发需求减量导致合格超出时，以物理来源链确认数量，在同一事务解除对应售后补发预留、记录用途转换及需求调整历史，并创建可追溯库存批次和入库流水。转库存数量 SHALL 只包含已完成适用路线且尚未确认补发的实物，不得同时计库存与可补发，不能恢复原发货库存；不得把转库存当补发确认。减量 SHALL 先减未覆盖部分，已覆盖部分必须明确去向；未核验计划不论日期或现场加工均可取消，但实物仍须按原合法去向修补入库或报废，取消不能证明未加工或放宽直接报废条件，在制实物必须继续适用路线，不能提前成为合格库存。补发需求 SHALL 不得低于已确认补发，调整必须保留原因及历史。

合格转库 SHALL 按 `completionEvidenceType` 校验完成证据，而非泛化为必须关联终点任务核验：加工来源使用 `TERMINAL_VERIFICATION` 并必有真实 `terminalVerificationId`；成品库存直接领用进入 `RESERVED` 的来源使用 `FINISHED_INVENTORY_ALLOCATION`，必有真实 `batchId`、allocation/line（`inventoryAllocationLineId`）、原出库 movementLine（`inventoryMovementLineId`）、`physicalShareId`、路线份额及本次用途转换，需求减量还 SHALL 关联 `demandAdjustmentId`。后一分支不要求不存在的 `terminalVerificationId`，但服务端 SHALL 由真实批次和领用/出库事实证明已完成适用路线、售后归属、原扣减有效及该份额尚未被消费，不能只凭客户端证据类型绕过校验；引用缺失、错链或数量超过剩余份额均拒绝。完成证据是来源追溯，不替代入库 origin 唯一键；origin 唯一键仍为 type/id/line，不以证据类型或单个 allocation line 禁止合法分次按份额转库。

Receipt 服务及全部 DTO SHALL 归 inventory，由 scheduling adapter 锁内解析来源/份额/终点后调用，不让 orders 直接引用库存类型，Receipt 不回调排班。普通余量 SHALL 使用 `OrderSurplusReceiptService`，必填 orderItemId/orderChangeItemId；售后 SHALL 使用 `AfterSalesInventoryReceiptService`，关联 afterSalesItemId 及适用 demandAdjustmentId/售后处置引用，不混用售后 FK。二者共用库存内部写核心及两类互斥完成证据；先改用途后完成的 origin 为真实终点核验分组并追溯原变更/调整，当场终点转库的 origin 为真实处置行。

成品领用的合格超出转库 SHALL 创建新批次及新入库流水，不恢复原领用批次，不冲销原领用出库，也不再次扣减原库存；对应 `reserved` 仅减少一次，原领用仍为有效已扣减事实。需求调整、用途转换、份额消费、解除预留、新批次/流水及入库来源关联 SHALL 同事务幂等落地，任一失败全部回滚，同键同载荷重放返回原结果；同键不同载荷不得生成另一笔事实。

领用取消与转库 SHALL 在相同领用及实物份额上互斥并锁内重验；原领用已有下游转库或补发消费时 SHALL 拒绝取消原领用，不能以剩余未消费份额为由整单冲销，也不能同时取消原领用恢复旧批次又转入新库。当前没有专用售后领用cancel入口，本修订不新增该入口；既有 `POST /api/inventory/movements/{id}/reverse` SHALL 保留合法领用冲销并接入统一锁序与新事实。合法冲销须相关计划已显式解除，全部接入份额未真实加工且未被核验/下游/发货/补发/转库等消费；任一份额已有上述事实均拒整笔逆向，不能因计划PENDING、未核验或已取消而豁免，不依赖执行字段、不新增人工确认步骤，同事务反向流入、来源、用途与覆盖并恢复库存一次；已终止/失效历史不复活。普通领用cancel与通用冲销对同一起因去重，不得各恢复一次。由排班终点核验或需求处置产生的入库流水不允许通过通用冲销孤立撤销，返回STATE_CANNOT_CANCEL；本change不新增核验/处置逆向能力，期初/调整等既有独立合法冲销不受误禁。若既有合法取消已先行生效，该领用不能再作为转库证据；这些防护不放宽任何既有取消前提。

#### Scenario: 五件合格保留三件补发两件入库
- **WHEN** 退 5 补 5 已完成全部适用加工，管理员填写原因将需求改成 3
- **THEN** 同一事务保留 3 件售后补发预留、解除另外 2 件预留并写入其库存批次及 2 件入库流水，保存来源和调整历史；已补发不增加且原发货库存不恢复

#### Scenario: 期初成品领用三件减需一件转新库存两件
- **WHEN** 合法期初成品批次无终点任务核验，领用 3 件兼容同路线成品进入售后 `RESERVED` 后，管理员填写原因将补发需求从 3 改成 1，并明确将超出 2 件转库存
- **THEN** 期初经真实 OpeningRequest/InventoryPage 显式路线和合法工艺输入冻结 inventory 自有证据，adapter 验证后关联排班快照；无缝3及有缝指定合法工艺版本均可按兼容路线验证。减量必须使用 GET 返回的真实 INVENTORY_INFLOW sourceId/allocationId，`FINISHED_INVENTORY_ALLOCATION` 追溯真实批次、allocation/line、原出库 movementLine、physicalShare、路线和需求调整；同事务保留预留及 terminalAvailableQuantity 为1、解除预留2、新建库存批次入库2，balance/executable仍0；原领用仅扣3一次且不恢复旧批次，不伪造终点任务/核验、不增加已补发，不用SQL补造来源或证据

#### Scenario: 成品领用减量转库同键重放不重复记账
- **WHEN** 上述 3 改 1、转库存 2 的请求以同一幂等键及相同载荷重放
- **THEN** 返回原结果，需求仍为 1、预留仍为 1，仅有原新批次的 2 件入库及一次解除预留，原领用仍仅扣 3 一次；origin 唯一键仍为 type/id/line，同键不同载荷拒绝

#### Scenario: 成品领用减量转库失败全部回滚
- **WHEN** 领用 3 件后需求 3 改 1 的转库事务在需求调整、用途转换、份额消费、解除预留、新批次、入库流水或来源关联任一写入失败
- **THEN** 需求和预留均保持 3，原领用有效且仅扣 3 一次，不留下部分调整、用途转换、份额消费、新批次或入库流水，成功幂等结果也不残留

#### Scenario: 成品领用取消与转库互斥
- **WHEN** 现有可达通用取消与售后转库竞争同一领用，或转库请求引用已按既有合法规则取消的领用
- **THEN** 锁内重验售后归属、领用状态与下游消费，通用取消不得误走普通回库逻辑；转库先成功则原领用取消拒绝，既有合法取消先成功则转库拒绝，不能同时恢复旧批次又生成新库存；不新增售后领用取消入口或 HTTP

#### Scenario: 领用已有下游消费拒绝取消
- **WHEN** 原售后领用任一份额已真实加工（即使计划未核验或已取消）、被核验/下游消费、已发货/补发或已转新库存，随后从现有可达通用取消入口请求取消原领用
- **THEN** 系统拒绝且不冲销原领用、不恢复旧批次、不改变预留、新库存或已补发事实，即使原领用尚有未消费份额也不能整单取消

#### Scenario: 完成证据不得缺失错链或冒用成品分支
- **WHEN** 无退回 `NORMAL` 加工转库缺少真实终点核验，或未到终点库存冒用 `FINISHED_INVENTORY_ALLOCATION`，或成品证据缺少真实领用/出库行、份额路线不符或超出未消费余额
- **THEN** 系统拒绝且不生成入库、不解除预留，不能将成品领用无需终点核验解释为全部来源证据可空；合法无退回 `NORMAL` 转库仍须 `TERMINAL_VERIFICATION` 及真实 `terminalVerificationId`

#### Scenario: 转库存与补发竞争同一实物
- **WHEN** 转库存事务与补发确认事务竞争同一份合格补发预留
- **THEN** 系统锁定并重算实物余额，仅允许符合剩余需求与可用量的事务生效，不得将同一实物既补发又入库，失败事务整体回滚

#### Scenario: 在制转库存须继续加工
- **WHEN** 减少补发需求影响到已实际加工的在制实物，管理员明确转库存
- **THEN** 系统保留历史计划与核验，迁移用途并移除该实物的补发覆盖，继续全部适用后续工序；路线终点才形成真实入库

#### Scenario: 无退回补发在制品转库存保留真实来源
- **WHEN** 无退回补发的NORMAL制作件因补发减量改库存用途并完成适用路线
- **THEN** 入库关联售后明细、正常加工来源、需求调整和终点核验，不要求不存在的退回核验或返工父源，不伪造关联

#### Scenario: 无实物额度终止不得虚构库存
- **WHEN** 无退回、无库存接入、确实无实物且未发生加工的正常制作授权5因补发需求减至3而合法终止2
- **THEN** 仅记录授权终止与覆盖释放，有效来源3，库存批次/流水和报废事实均不新增；退回件、库存接入件或在制件不得冒用此分支，PENDING/未核验/计划取消不构成无实物且未加工的证明，不新增开始字段或人工确认步骤

#### Scenario: 不得转移已确认补发实物
- **WHEN** 已确认补发 4 件后请求将需求降到 3，或将已确认补发数量转库存
- **THEN** 系统拒绝并保留原需求、补发事实和库存余额，不能通过库存操作绕过已补发下限
