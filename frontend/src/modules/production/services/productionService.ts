import { api } from '@/platform/services/api'
import { newIdempotencyKey } from '@/platform/services/idempotency'
import type {
  CorrectionFeedItem,
  DefectReasonStatus,
  DefectReasonView,
  DispositionView,
  OrderProductionTrace,
  PhysicalDestination,
  ProductionProjectionRow,
  ResponsibilityType,
  TargetProcess,
  TaskType,
  ProductionOverview,
  TaskView,
} from '@/modules/production/contracts'

export interface ArrangeInput {
  taskType: TaskType
  subject: { subjectType: 'EMPLOYEE' | 'TEAM'; subjectBusinessId: string }
  workDate: string
  processCode: TargetProcess | null
  items: Array<{ productionSourceBusinessId: string; plannedQuantity: number }>
  enteredStandardMinutes: number | null
  otherTask: {
    workContent: string
    relatedProductBusinessId: string | null
    plannedQuantity: number | null
  } | null
  overrideReason: string | null
}

export interface AdjustTaskInput {
  subject: { subjectType: 'EMPLOYEE' | 'TEAM'; subjectBusinessId: string }
  workDate: string
  processCode: TargetProcess | null
  items: Array<{
    taskItemBusinessId: string | null
    productionSourceBusinessId: string
    plannedQuantity: number
  }>
  enteredStandardMinutes: number | null
  otherTask: {
    workContent: string
    relatedProductBusinessId: string | null
    plannedQuantity: number | null
  } | null
  overrideReason: string | null
}

export interface VerifyItemInput {
  taskItemBusinessId: string
  unfinishedQuantity: number
  qualifiedQuantity: number
  reworkQuantity: number
  scrappedQuantity: number
  defects: Array<{
    defectReasonCode: string
    defectReasonText: string | null
    quantity: number
    reworkQuantity: number
    scrapParts: Array<{
      quantity: number
      physicalDestination: PhysicalDestination
      responsibilityType: ResponsibilityType
      responsibleSubjectBusinessId: string | null
      waivedReason: string | null
    }>
  }>
  resultNote: string | null
}

export const productionService = {
  tasks(workDate: string): Promise<TaskView[]> {
    return api.query<TaskView[]>(`/api/production/tasks?workDate=${workDate}`)
  },

  overview(): Promise<ProductionOverview> {
    return api.query<ProductionOverview>('/api/production/tasks/overview')
  },

  recentTraces(offset = 0, limit = 50): Promise<OrderProductionTrace[]> {
    return api.query<OrderProductionTrace[]>(
      `/api/production/traces?offset=${offset}&limit=${limit}`,
    )
  },

  tasksBetween(startDate?: string, endDate?: string): Promise<TaskView[]> {
    const search = new URLSearchParams()
    if (startDate !== undefined) {
      search.set('startDate', startDate)
    }
    if (endDate !== undefined) {
      search.set('endDate', endDate)
    }
    const text = search.toString()
    return api.query<TaskView[]>(`/api/production/tasks${text === '' ? '' : `?${text}`}`)
  },

  task(taskBusinessId: string): Promise<TaskView> {
    return api.query<TaskView>(`/api/production/tasks/${encodeURIComponent(taskBusinessId)}`)
  },

  arrange(input: ArrangeInput): Promise<{ taskBusinessId: string }> {
    return api.command<{ taskBusinessId: string }, ArrangeInput>(
      '/api/production/tasks/arrangements',
      { idempotencyKey: newIdempotencyKey('task-arrange'), body: input },
    )
  },

  adjustTask(taskBusinessId: string, input: AdjustTaskInput): Promise<TaskView> {
    return api.command<TaskView, AdjustTaskInput>(
      `/api/production/tasks/${encodeURIComponent(taskBusinessId)}/adjustment`,
      { idempotencyKey: newIdempotencyKey('task-adjust'), body: input },
    )
  },

  verify(items: VerifyItemInput[]): Promise<{ taskBusinessId: string }> {
    return api.command<{ taskBusinessId: string }, { items: VerifyItemInput[] }>(
      '/api/production/tasks/verification',
      { idempotencyKey: newIdempotencyKey('task-verify'), body: { items } },
    )
  },

  cancelTask(
    taskBusinessId: string,
    reasonText: string,
  ): Promise<{ taskBusinessId: string; status: string }> {
    return api.command(`/api/production/tasks/${encodeURIComponent(taskBusinessId)}/cancellation`, {
      idempotencyKey: newIdempotencyKey('task-cancel'),
      body: { reasonText },
    })
  },

  verifyOther(input: {
    taskBusinessId: string
    completedQuantity: number | null
    unfinishedQuantity: number | null
    resultNote: string | null
  }): Promise<{ taskBusinessId: string }> {
    return api.command<{ taskBusinessId: string }, typeof input>(
      '/api/production/tasks/other-verification',
      { idempotencyKey: newIdempotencyKey('other-task-verify'), body: input },
    )
  },

  demands(productBusinessId: string): Promise<ProductionProjectionRow[]> {
    return api.query<ProductionProjectionRow[]>(
      `/api/production/tasks/demands?productBusinessId=${encodeURIComponent(productBusinessId)}`,
    )
  },

  offlineTransfers(
    productionSourceBusinessId: string,
  ): Promise<import('@/modules/production/contracts').OfflineTransferView[]> {
    return api.query(
      `/api/production/offline-transfers?productionSourceBusinessId=${encodeURIComponent(productionSourceBusinessId)}`,
    )
  },

  orderTrace(orderItemBusinessId: string): Promise<OrderProductionTrace> {
    return api.query<OrderProductionTrace>(
      `/api/production/orders/${encodeURIComponent(orderItemBusinessId)}/trace`,
    )
  },

  dispositions(taskItemBusinessId: string): Promise<DispositionView[]> {
    return api.query<DispositionView[]>(
      `/api/production/quality/dispositions?taskItemBusinessId=${encodeURIComponent(taskItemBusinessId)}`,
    )
  },

  corrections(feedSequence = 0, limit = 50): Promise<CorrectionFeedItem[]> {
    return api.query<CorrectionFeedItem[]>(
      `/api/production/corrections?feedSequence=${feedSequence}&limit=${limit}`,
    )
  },

  defectReasons(): Promise<DefectReasonView[]> {
    return api.query<DefectReasonView[]>('/api/production/quality/defect-reasons')
  },

  saveDefectReason(input: {
    defectReasonCode: string
    name: string
    other: boolean
    expectedRowVersion: number
  }): Promise<DefectReasonView> {
    return api.command<DefectReasonView, typeof input>('/api/production/quality/defect-reasons', {
      idempotencyKey: newIdempotencyKey('defect-reason'),
      body: input,
    })
  },

  changeDefectReasonStatus(
    code: string,
    status: DefectReasonStatus,
    expectedRowVersion: number,
  ): Promise<DefectReasonView> {
    return api.command<
      DefectReasonView,
      { status: DefectReasonStatus; expectedRowVersion: number }
    >(`/api/production/quality/defect-reasons/${encodeURIComponent(code)}/status`, {
      idempotencyKey: newIdempotencyKey('defect-reason-status'),
      body: { status, expectedRowVersion },
    })
  },

  correct(input: {
    taskItemBusinessId: string
    correctedResult: VerifyItemInput
    reason: string
  }): Promise<{ correctionCommandBusinessId: string }> {
    return api.command<{ correctionCommandBusinessId: string }, typeof input>(
      '/api/production/quality/corrections',
      { idempotencyKey: newIdempotencyKey('production-correction'), body: input },
    )
  },

  correctResponsibility(input: {
    dispositionFactBusinessId: string
    responsibilityType: ResponsibilityType
    responsibleSubjectBusinessId: string | null
    waivedReason: string | null
    reasonText: string
    expectedRowVersion: number
  }): Promise<{ responsibilityChainBusinessId: string }> {
    return api.command<{ responsibilityChainBusinessId: string }, typeof input>(
      '/api/production/quality/responsibility-corrections',
      { idempotencyKey: newIdempotencyKey('responsibility-correction'), body: input },
    )
  },
}
