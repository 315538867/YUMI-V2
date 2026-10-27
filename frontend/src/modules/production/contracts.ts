import type { CodeOf } from '@/platform/display/codeCatalog'

export type TaskType = CodeOf<'production.taskType'>
export type TaskStatus = CodeOf<'production.taskStatus'>
export type TargetProcess = CodeOf<'production.targetProcess'>
export type PhysicalDestination = CodeOf<'production.physicalDestination'>
export type ResponsibilityType = CodeOf<'production.responsibilityType'>
export type DefectReasonStatus = CodeOf<'production.defectReasonStatus'>
export type VerificationStatus = CodeOf<'production.verificationStatus'>

export interface ProductionOverviewProcess {
  process: TargetProcess
  requiredQuantity: number
  scheduledQuantity: number
  completedQuantity: number
  remainingQuantity: number
}

export interface ProductionOverview {
  orderCount: number
  requiredQuantity: number
  scheduledQuantity: number
  productionFactQuantity: number
  fulfilledQuantity: number
  remainingQuantity: number
  processes: ProductionOverviewProcess[]
}

export interface TaskItemView {
  taskItemBusinessId: string
  itemOrder: number
  demandBusinessId: string
  demandType: CodeOf<'production.demandType'>
  productionSourceBusinessId: string | null
  repairSourceBusinessId: string | null
  productBusinessId: string
  plannedQuantity: number
  itemStandardMinutes: number
  verificationStatus: VerificationStatus
}

export interface TaskView {
  taskBusinessId: string
  taskNo: string
  taskType: TaskType
  status: TaskStatus
  workDate: string
  subject: { subjectType: CodeOf<'production.subjectType'>; subjectBusinessId: string }
  processCode: TargetProcess | null
  plannedWorkQuantity: number
  taskStandardSeconds: number
  items: TaskItemView[]
  assessment: {
    assessmentBusinessId: string
    standardSeconds: number
    availableEffectiveSeconds: number
    assessmentResult: CodeOf<'production.workloadComparison'>
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
  demandType: CodeOf<'production.demandType'>
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
      dispositionType: CodeOf<'production.defectDispositionType'>
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
  itemKind: CodeOf<'production.correctionItemKind'>
  effectType: CodeOf<'production.correctionEffectType'>
  referencedFactBusinessId: string | null
  quantity: number
  businessDate: string
}

export interface OrderProductionTrace {
  orderBusinessId: string
  orderNo: string
  orderItemBusinessId: string
  productNames: string
  demands: Array<{
    demandBusinessId: string
    demandType: CodeOf<'production.demandType'>
    productionSourceBusinessId: string
    physicalRootSourceBusinessId: string
    productBusinessId: string
    targetProcess: TargetProcess
    status: CodeOf<'production.demandStatus'>
    ledger: unknown
    tasks: Array<{
      taskBusinessId: string
      taskNo: string
      taskType: TaskType
      workDate: string
      processCode: TargetProcess | null
      subjectType: CodeOf<'production.subjectType'>
      subjectBusinessId: string
      plannedQuantity: number
      status: TaskStatus
      items: Array<{
        taskItemBusinessId: string
        plannedQuantity: number
        completedQuantity: number
        unfinishedQuantity: number
        qualifiedQuantity: number
        reworkQuantity: number
        scrappedQuantity: number
        verificationStatus: VerificationStatus
      }>
    }>
    facts: Array<{
      factKind: CodeOf<'production.factKind'>
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
  factKind: CodeOf<'production.factKind'>
  factBusinessId: string
  productionSourceBusinessId: string
  quantity: number
  occurredAt: string
  detail: string | null
  destination: string
}
