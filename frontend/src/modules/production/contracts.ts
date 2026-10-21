export type TaskType = 'NORMAL_PRODUCTION' | 'EXTRA_PRODUCTION' | 'REPAIR' | 'OTHER_TASK'
export type TaskStatus = 'ARRANGED' | 'VERIFIED' | 'CANCELLED' | 'PARTIALLY_VERIFIED'
export type TargetProcess = 'MAKING' | 'BAGGING' | 'SEAM_CUTTING'
export type PhysicalDestination = 'IN_SYSTEM' | 'DESTROYED' | 'TRANSFERRED_OFFLINE_INVENTORY'
export type ResponsibilityType = 'EMPLOYEE' | 'TEAM' | 'STUDIO' | 'CUSTOMER' | 'WAIVED'
export type DefectReasonStatus = 'ACTIVE' | 'INACTIVE'

export interface TaskItemView {
  taskItemBusinessId: string
  itemOrder: number
  demandBusinessId: string
  demandType: string
  productionSourceBusinessId: string | null
  repairSourceBusinessId: string | null
  productBusinessId: string
  plannedQuantity: number
  itemStandardMinutes: number
  verificationStatus: string
}

export interface TaskView {
  taskBusinessId: string
  taskNo: string
  taskType: TaskType
  status: TaskStatus
  workDate: string
  subject: { subjectType: 'EMPLOYEE' | 'TEAM'; subjectBusinessId: string }
  processCode: TargetProcess | null
  plannedWorkQuantity: number
  taskStandardSeconds: number
  items: TaskItemView[]
  assessment: {
    assessmentBusinessId: string
    standardSeconds: number
    availableEffectiveSeconds: number
    assessmentResult: string
  } | null
  otherTask: {
    workContent: string
    relatedProductBusinessId: string | null
    plannedQuantity: number | null
    frozenUnitLaborAmount: string | null
    resultNote: string | null
  } | null
}

export interface ProductionProjectionRow {
  demandBusinessId: string
  productionSourceBusinessId: string
  orderItemBusinessId: string | null
  productBusinessId: string
  demandType: string
  requiredQuantity: number
  pendingWorkQuantity: number
  inProcessQuantity: number
  targetReadyQuantity: number
  fulfilledQuantity: number
  fulfillmentGapQuantity: number
  inSystemQuantity: number
  consumedQuantity: number
  destroyedQuantity: number
  offlineInventoryQuantity: number
  updatedAt: string
}

export interface DispositionView {
  taskItemBusinessId: string
  demandBusinessId: string
  productionSourceBusinessId: string
  defects: Array<{
    defectFactBusinessId: string
    defectReasonCode: string
    defectReasonName: string
    quantity: number
    reworkQuantity: number
    scrappedQuantity: number
    dispositions: Array<{
      dispositionFactBusinessId: string
      dispositionType: string
      physicalDestination: PhysicalDestination
      quantity: number
      terminalExitBusinessId: string | null
      repairSourceBusinessId: string | null
      responsibilityType: ResponsibilityType
      responsibleSubjectBusinessId: string | null
      waivedReason: string | null
    }>
  }>
}

export interface DefectReasonView {
  defectReasonCode: string
  name: string
  status: DefectReasonStatus
  other: boolean
  rowVersion: number
}

export interface CorrectionFeedItem {
  feedSequence: number
  correctionCommandBusinessId: string
  taskItemBusinessId: string
  itemKind: string
  effectType: string
  referencedFactBusinessId: string | null
  quantity: number
  businessDate: string
}

export interface OrderProductionTrace {
  orderBusinessId: string
  orderItemBusinessId: string
  demands: Array<{
    demandBusinessId: string
    demandType: string
    productionSourceBusinessId: string
    physicalRootSourceBusinessId: string
    productBusinessId: string
    targetProcess: TargetProcess
    status: string
    ledger: unknown
    tasks: Array<{
      taskBusinessId: string
      taskNo: string
      taskType: string
      workDate: string
      processCode: string | null
      subjectType: string
      subjectBusinessId: string
      plannedQuantity: number
      status: string
      items: Array<{
        taskItemBusinessId: string
        plannedQuantity: number
        completedQuantity: number
        unfinishedQuantity: number
        qualifiedQuantity: number
        reworkQuantity: number
        scrappedQuantity: number
        verificationStatus: string
      }>
    }>
    facts: Array<{
      factKind: string
      factBusinessId: string
      productionSourceBusinessId: string
      occurredAt: string
      quantity: number
      detail: string | null
    }>
    gapReasons: string[]
    delayReasons: string[]
  }>
}

export interface OfflineTransferView {
  factKind: string
  factBusinessId: string
  productionSourceBusinessId: string
  quantity: number
  occurredAt: string
  detail: string | null
  destination: string
}
