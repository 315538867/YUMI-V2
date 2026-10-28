<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { codeLabel, codesOf } from '@/platform/display/codeCatalog'
import type {
  DefectReasonView,
  PhysicalDestination,
  ResponsibilityType,
  TaskView,
} from '@/modules/production/contracts'
import {
  productionService,
  type VerifyItemInput,
} from '@/modules/production/services/productionService'
import CodeTag from '@/shared/components/CodeTag.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'
import PageShell from '@/shared/components/PageShell.vue'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'

const { loading, errorText, run } = useAsyncLoad(true)
const { loading: saving, run: runAction } = useAsyncAction()

interface ItemForm {
  taskItemBusinessId: string
  plannedQuantity: number
  unfinishedQuantity: number
  qualifiedQuantity: number
  reworkQuantity: number
  scrappedQuantity: number
  defectReasonCode: string
  defectReasonText: string
  destination: PhysicalDestination
  responsibilityType: ResponsibilityType | ''
  responsibleSubjectBusinessId: string
  waivedReason: string
  offlineInventoryName: string
  offlineInventoryNote: string
  handedOverBy: string
  receivedBy: string
  transferredAt: string
}

const route = useRoute()
const taskId = typeof route.query.task === 'string' ? route.query.task : ''
const task = ref<TaskView | null>(null)
const items = ref<ItemForm[]>([])
const reasons = ref<DefectReasonView[]>([])
const otherCompleted = ref(0)
const otherUnfinished = ref(0)
const resultNote = ref('')
const successText = ref('')
const submitError = ref('')

const responsibilityOptions = codesOf('production.responsibilityType')

function today(): string {
  return new Date().toISOString().slice(0, 10)
}

function defaults(item: TaskView['items'][number]): ItemForm {
  return {
    taskItemBusinessId: item.taskItemBusinessId,
    plannedQuantity: item.plannedQuantity,
    unfinishedQuantity: 0,
    qualifiedQuantity: item.plannedQuantity,
    reworkQuantity: 0,
    scrappedQuantity: 0,
    defectReasonCode: '',
    defectReasonText: '',
    destination: 'DESTROYED',
    responsibilityType: '',
    responsibleSubjectBusinessId: '',
    waivedReason: '',
    offlineInventoryName: '',
    offlineInventoryNote: '',
    handedOverBy: '',
    receivedBy: '',
    transferredAt: today(),
  }
}

async function load(): Promise<void> {
  await run(async () => {
    reasons.value = (await productionService.defectReasons()).filter(
      (reason) => reason.status === 'ACTIVE',
    )
    if (taskId !== '') {
      task.value = await productionService.task(taskId)
      items.value = task.value.items
        .filter((item) => item.verificationStatus === 'PENDING')
        .map(defaults)
      otherCompleted.value = task.value.plannedWorkQuantity
      otherUnfinished.value = 0
    }
  }, '任务加载失败')
}

function defectQuantity(item: ItemForm): number {
  return Number(item.reworkQuantity) + Number(item.scrappedQuantity)
}

function reasonIsOther(item: ItemForm): boolean {
  return (
    reasons.value.find((reason) => reason.defectReasonCode === item.defectReasonCode)?.other ===
    true
  )
}

function itemError(item: ItemForm): string | null {
  const planned = Number(item.plannedQuantity)
  const total =
    Number(item.unfinishedQuantity) +
    Number(item.qualifiedQuantity) +
    Number(item.reworkQuantity) +
    Number(item.scrappedQuantity)
  if (total !== planned) {
    return `计划 ${planned} 件，但未完成 + 合格 + 返工 + 报废合计为 ${total} 件`
  }
  const defects = defectQuantity(item)
  if (defects === 0) {
    return null
  }
  if (item.defectReasonCode === '') {
    return '存在返工或报废时必须选择缺陷原因'
  }
  if (reasonIsOther(item) && item.defectReasonText.trim() === '') {
    return '缺陷原因为“其他”时必须填写说明'
  }
  if (Number(item.scrappedQuantity) > 0) {
    if (item.responsibilityType === '') {
      return '存在报废时必须明确责任主体或免除结论'
    }
    if (item.responsibilityType === 'WAIVED' && item.waivedReason.trim() === '') {
      return '责任免除必须填写原因'
    }
    if (item.responsibilityType !== 'WAIVED' && item.responsibleSubjectBusinessId.trim() === '') {
      return '责任主体必须填写稳定标识'
    }
    if (item.destination === 'TRANSFERRED_OFFLINE_INVENTORY') {
      if (item.offlineInventoryName.trim() === '') return '转线下库存必须填写库存名称'
      if (item.handedOverBy.trim() === '') return '转线下库存必须填写交接人'
      if (item.receivedBy.trim() === '') return '转线下库存必须填写接收人'
      if (item.transferredAt === '') return '转线下库存必须填写移交日期'
    }
  }
  return null
}

function toVerifyInput(item: ItemForm): VerifyItemInput {
  const defects = defectQuantity(item)
  const offline = item.destination === 'TRANSFERRED_OFFLINE_INVENTORY'
  return {
    taskItemBusinessId: item.taskItemBusinessId,
    unfinishedQuantity: Number(item.unfinishedQuantity),
    qualifiedQuantity: Number(item.qualifiedQuantity),
    reworkQuantity: Number(item.reworkQuantity),
    scrappedQuantity: Number(item.scrappedQuantity),
    defects:
      defects === 0
        ? []
        : [
            {
              defectReasonCode: item.defectReasonCode,
              defectReasonText:
                item.defectReasonText.trim() === '' ? null : item.defectReasonText.trim(),
              quantity: defects,
              reworkQuantity: Number(item.reworkQuantity),
              scrapParts:
                Number(item.scrappedQuantity) === 0
                  ? []
                  : [
                      {
                        quantity: Number(item.scrappedQuantity),
                        physicalDestination: item.destination,
                        responsibilityType: item.responsibilityType as ResponsibilityType,
                        responsibleSubjectBusinessId:
                          item.responsibilityType === 'WAIVED'
                            ? null
                            : item.responsibleSubjectBusinessId.trim(),
                        waivedReason:
                          item.responsibilityType === 'WAIVED' ? item.waivedReason.trim() : null,
                        offlineInventoryName: offline ? item.offlineInventoryName.trim() : null,
                        offlineInventoryNote: offline
                          ? item.offlineInventoryNote.trim() || null
                          : null,
                        handedOverBy: offline ? item.handedOverBy.trim() : null,
                        receivedBy: offline ? item.receivedBy.trim() : null,
                        transferredAt: offline ? item.transferredAt : null,
                      },
                    ],
            },
          ],
    resultNote: resultNote.value === '' ? null : resultNote.value,
  }
}

async function submit(): Promise<void> {
  if (task.value === null) {
    return
  }
  submitError.value = ''
  successText.value = ''
  for (const [index, item] of items.value.entries()) {
    const error = itemError(item)
    if (error !== null) {
      submitError.value = `明细 ${index + 1}：${error}`
      return
    }
  }
  await runAction(
    async () => {
      if (task.value!.taskType === 'OTHER_TASK') {
        await productionService.verifyOther({
          taskBusinessId: task.value!.taskBusinessId,
          completedQuantity:
            task.value!.otherTask?.plannedQuantity == null ? null : Number(otherCompleted.value),
          unfinishedQuantity:
            task.value!.otherTask?.plannedQuantity == null ? null : Number(otherUnfinished.value),
          resultNote: resultNote.value === '' ? null : resultNote.value,
        })
      } else {
        await productionService.verify(items.value.map(toVerifyInput))
      }
      task.value = await productionService.task(task.value!.taskBusinessId)
      items.value = task.value.items
        .filter((item) => item.verificationStatus === 'PENDING')
        .map(defaults)
    },
    {
      success: '核验已提交；数量与事实以服务端结果为准',
      fallback: '核验失败',
      onSuccess: () => {
        successText.value = '核验已提交；数量与事实以服务端结果为准'
      },
    },
  )
}

onMounted(load)
</script>

<template>
  <PageShell title="任务核验">
    <WorkspacePage v-loading="loading" class="verify-page">
      <el-alert
        v-if="taskId === ''"
        type="info"
        :closable="false"
        title="请从排班或任务详情进入核验"
      />
      <el-alert
        v-if="errorText"
        type="error"
        :closable="false"
        :title="errorText"
        class="yumi-alert"
      />
      <el-alert
        v-if="submitError"
        type="error"
        :closable="false"
        :title="submitError"
        class="yumi-alert"
      />
      <el-alert
        v-if="successText"
        type="success"
        :closable="false"
        :title="successText"
        class="yumi-alert"
      />

      <template v-if="task">
        <el-descriptions :column="3" border class="summary">
          <el-descriptions-item label="任务">{{ task.taskNo }}</el-descriptions-item>
          <el-descriptions-item label="类型"
            ><CodeTag category="production.taskType" :code="task.taskType"
          /></el-descriptions-item>
          <el-descriptions-item label="状态"
            ><CodeTag category="production.taskStatus" :code="task.status"
          /></el-descriptions-item>
        </el-descriptions>

        <template v-if="task.taskType === 'OTHER_TASK'">
          <el-form label-width="120px" class="form">
            <el-form-item v-if="task.otherTask?.plannedQuantity != null" label="完成数量">
              <el-input v-model.number="otherCompleted" type="number" />
            </el-form-item>
            <el-form-item v-if="task.otherTask?.plannedQuantity != null" label="未完成数量">
              <el-input v-model.number="otherUnfinished" type="number" />
            </el-form-item>
            <el-form-item label="核验说明">
              <el-input v-model="resultNote" type="textarea" :rows="2" />
            </el-form-item>
          </el-form>
        </template>

        <template v-else>
          <el-empty v-if="items.length === 0" description="该任务没有待核验明细" />
          <div v-for="(item, index) in items" :key="item.taskItemBusinessId" class="verify-item">
            <div class="yumi-panel__header">
              <div class="verify-item__copy">
                <h2>明细 {{ index + 1 }}</h2>
                <span class="yumi-hint"
                  >计划 {{ item.plannedQuantity }} 件 · {{ item.taskItemBusinessId }}</span
                >
              </div>
            </div>
            <el-form label-width="96px" class="yumi-panel__body">
              <div class="verify-grid">
                <el-form-item label="未完成">
                  <el-input v-model.number="item.unfinishedQuantity" type="number" />
                </el-form-item>
                <el-form-item label="合格">
                  <el-input v-model.number="item.qualifiedQuantity" type="number" />
                </el-form-item>
                <el-form-item label="返工/修补">
                  <el-input v-model.number="item.reworkQuantity" type="number" />
                </el-form-item>
                <el-form-item label="线上报废">
                  <el-input v-model.number="item.scrappedQuantity" type="number" />
                </el-form-item>
              </div>

              <template v-if="defectQuantity(item) > 0">
                <h3 class="verify-section">缺陷与责任</h3>
                <div class="verify-grid">
                  <el-form-item label="缺陷原因">
                    <el-select v-model="item.defectReasonCode" placeholder="请选择缺陷原因">
                      <el-option
                        v-for="reason in reasons"
                        :key="reason.defectReasonCode"
                        :label="reason.name"
                        :value="reason.defectReasonCode"
                      />
                    </el-select>
                  </el-form-item>
                  <el-form-item v-if="reasonIsOther(item)" label="其他说明">
                    <el-input v-model="item.defectReasonText" />
                  </el-form-item>
                </div>

                <template v-if="Number(item.scrappedQuantity) > 0">
                  <div class="verify-grid">
                    <el-form-item label="实物去向">
                      <el-select v-model="item.destination">
                        <el-option
                          v-for="code in ['DESTROYED', 'TRANSFERRED_OFFLINE_INVENTORY']"
                          :key="code"
                          :label="codeLabel('production.physicalDestination', code)"
                          :value="code"
                        />
                      </el-select>
                    </el-form-item>
                    <el-form-item label="责任结论">
                      <el-select v-model="item.responsibilityType" placeholder="请选择责任结论">
                        <el-option
                          v-for="code in responsibilityOptions"
                          :key="code"
                          :label="codeLabel('production.responsibilityType', code)"
                          :value="code"
                        />
                      </el-select>
                    </el-form-item>
                    <el-form-item v-if="item.responsibilityType === 'WAIVED'" label="免除原因">
                      <el-input v-model="item.waivedReason" />
                    </el-form-item>
                    <el-form-item v-else label="责任主体">
                      <el-input
                        v-model="item.responsibleSubjectBusinessId"
                        placeholder="员工 / 小组 / 客户业务标识"
                      />
                    </el-form-item>
                  </div>

                  <div
                    v-if="item.destination === 'TRANSFERRED_OFFLINE_INVENTORY'"
                    class="verify-grid verify-grid--offline"
                  >
                    <el-form-item label="库存名称">
                      <el-input v-model="item.offlineInventoryName" />
                    </el-form-item>
                    <el-form-item label="交接人">
                      <el-input v-model="item.handedOverBy" />
                    </el-form-item>
                    <el-form-item label="接收人">
                      <el-input v-model="item.receivedBy" />
                    </el-form-item>
                    <el-form-item label="移交日期">
                      <el-date-picker
                        v-model="item.transferredAt"
                        type="date"
                        value-format="YYYY-MM-DD"
                      />
                    </el-form-item>
                    <el-form-item label="库存说明" class="verify-grid__wide">
                      <el-input v-model="item.offlineInventoryNote" type="textarea" :rows="2" />
                    </el-form-item>
                  </div>
                </template>
              </template>
            </el-form>
          </div>

          <el-form label-width="90px" class="form">
            <el-form-item label="核验说明">
              <el-input v-model="resultNote" type="textarea" :rows="2" />
            </el-form-item>
          </el-form>
          <p class="yumi-hint">
            已核验明细不再显示；返工与报废共同构成缺陷事实，不会重复增加完成量。
          </p>
        </template>

        <el-button
          type="primary"
          :loading="saving"
          :disabled="task.taskType !== 'OTHER_TASK' && items.length === 0"
          @click="submit"
          >提交最终核验</el-button
        >
      </template>
    </WorkspacePage>
  </PageShell>
</template>

<style scoped>
.summary {
  margin-bottom: var(--yumi-space-4);
}

.form {
  margin-top: var(--yumi-space-4);
}

.verify-item {
  border: 1px solid var(--yumi-border-color);
  border-radius: var(--yumi-radius-lg);
}

.verify-item + .verify-item {
  margin-top: var(--yumi-space-3);
}

.verify-item__copy {
  display: flex;
  align-items: baseline;
  gap: var(--yumi-space-3);
  flex-wrap: wrap;
}

.verify-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0 var(--yumi-space-4);
}

.verify-grid__wide {
  grid-column: 1 / -1;
}

.verify-section {
  margin: var(--yumi-space-2) 0 0;
}

@media (max-width: 1100px) {
  .verify-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 600px) {
  .verify-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
