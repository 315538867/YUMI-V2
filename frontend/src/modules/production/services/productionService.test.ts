import { describe, expect, it, vi } from 'vitest'
import { productionService } from '@/modules/production/services/productionService'

const query = vi.fn()
const command = vi.fn()

vi.mock('@/platform/services/api', () => ({
  api: {
    query: (...args: unknown[]) => query(...args),
    command: (...args: unknown[]) => command(...args),
  },
}))

describe('生产 service', () => {
  it('其他任务不携带生产明细，主体使用嵌套引用', async () => {
    command.mockResolvedValue({ taskBusinessId: 'TASK-1' })
    await productionService.arrange({
      taskType: 'OTHER_TASK',
      subject: { subjectType: 'EMPLOYEE', subjectBusinessId: 'EMP-1' },
      workDate: '2026-10-20',
      processCode: null,
      items: [],
      enteredStandardMinutes: 45,
      otherTask: {
        workContent: '整理库位',
        relatedProductBusinessId: null,
        plannedQuantity: null,
      },
      overrideReason: null,
    })
    const [, options] = command.mock.calls[0] as [string, Record<string, unknown>]
    const body = options.body as Record<string, unknown>
    expect(body.subject).toEqual({ subjectType: 'EMPLOYEE', subjectBusinessId: 'EMP-1' })
    expect(body.items).toEqual([])
    expect('subjectType' in body).toBe(false)
  })

  it('任务调整提交未核验明细与现有明细标识', async () => {
    command.mockResolvedValue({ taskBusinessId: 'TASK-1' })
    await productionService.adjustTask('TASK-1', {
      subject: { subjectType: 'EMPLOYEE', subjectBusinessId: 'EMP-2' },
      workDate: '2026-10-21',
      processCode: 'MAKING',
      items: [
        {
          taskItemBusinessId: 'TI-1',
          productionSourceBusinessId: 'SRC-1',
          plannedQuantity: 2,
        },
        {
          taskItemBusinessId: null,
          productionSourceBusinessId: 'SRC-2',
          plannedQuantity: 1,
        },
      ],
      enteredStandardMinutes: null,
      otherTask: null,
      overrideReason: null,
    })
    const [path, options] = command.mock.calls[0] as [string, Record<string, unknown>]
    expect(path).toBe('/api/production/tasks/TASK-1/adjustment')
    const body = options.body as {
      items: Array<{ taskItemBusinessId: string | null }>
    }
    expect(body.items.map((item) => item.taskItemBusinessId)).toEqual(['TI-1', null])
  })

  it('报废数量只出现一次并携带独立去向', async () => {
    command.mockResolvedValue({ taskBusinessId: 'TASK-1' })
    await productionService.verify([
      {
        taskItemBusinessId: 'TI-1',
        unfinishedQuantity: 0,
        qualifiedQuantity: 1,
        reworkQuantity: 0,
        scrappedQuantity: 1,
        defects: [
          {
            defectReasonCode: 'OTHER',
            defectReasonText: '验收',
            quantity: 1,
            reworkQuantity: 0,
            scrapParts: [
              {
                quantity: 1,
                physicalDestination: 'TRANSFERRED_OFFLINE_INVENTORY',
                responsibilityType: 'WAIVED',
                responsibleSubjectBusinessId: null,
                waivedReason: '验收免除',
              },
            ],
          },
        ],
        resultNote: null,
      },
    ])
    const [, options] = command.mock.calls[0] as [string, Record<string, unknown>]
    const item = (options.body as { items: Array<Record<string, unknown>> }).items[0]!
    expect(item.scrappedQuantity).toBe(1)
    expect(
      (item.defects as Array<{ scrapParts: Array<{ physicalDestination: string }> }>)[0]
        ?.scrapParts[0]?.physicalDestination,
    ).toBe('TRANSFERRED_OFFLINE_INVENTORY')
  })
})
