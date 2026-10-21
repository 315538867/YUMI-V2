<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { PhysicalDestination, TaskView } from '@/modules/production/contracts'
import { productionService, type VerifyItemInput } from '@/modules/production/services/productionService'

interface ItemForm {
  taskItemBusinessId: string
  plannedQuantity: number
  unfinishedQuantity: number
  qualifiedQuantity: number
  reworkQuantity: number
  scrappedQuantity: number
  destination: PhysicalDestination
}

const route = useRoute()
const taskId = typeof route.query.task === 'string' ? route.query.task : ''
const task = ref<TaskView | null>(null)
const items = ref<ItemForm[]>([])
const otherCompleted = ref(0)
const otherUnfinished = ref(0)
const resultNote = ref('')
const loading = ref(true)
const saving = ref(false)
const errorText = ref('')
const successText = ref('')

function defaults(item: { taskItemBusinessId: string; plannedQuantity: number }): ItemForm {
  return {
    taskItemBusinessId: item.taskItemBusinessId,
    plannedQuantity: item.plannedQuantity,
    unfinishedQuantity: 0,
    qualifiedQuantity: item.plannedQuantity,
    reworkQuantity: 0,
    scrappedQuantity: 0,
    destination: 'DESTROYED',
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    if (taskId !== '') {
      task.value = await productionService.task(taskId)
      items.value = task.value.items.map(defaults)
      otherCompleted.value = task.value.plannedWorkQuantity
      otherUnfinished.value = 0
    }
  } catch (error) {
    errorText.value = userMessageOf(error, '任务加载失败')
  } finally {
    loading.value = false
  }
}

function toVerifyInput(item: ItemForm): VerifyItemInput {
  const defectQuantity = item.reworkQuantity + item.scrappedQuantity
  const defects =
    defectQuantity === 0
      ? []
      : [
          {
            defectReasonCode: 'OTHER',
            defectReasonText: '验收登记',
            quantity: defectQuantity,
            reworkQuantity: item.reworkQuantity,
            scrapParts:
              item.scrappedQuantity === 0
                ? []
                : [
                    {
                      quantity: item.scrappedQuantity,
                      physicalDestination: item.destination,
                      responsibilityType: 'WAIVED' as const,
                      responsibleSubjectBusinessId: null,
                      waivedReason: '验收登记免除',
                    },
                  ],
          },
        ]
  return {
    taskItemBusinessId: item.taskItemBusinessId,
    unfinishedQuantity: Number(item.unfinishedQuantity),
    qualifiedQuantity: Number(item.qualifiedQuantity),
    reworkQuantity: Number(item.reworkQuantity),
    scrappedQuantity: Number(item.scrappedQuantity),
    defects,
    resultNote: resultNote.value === '' ? null : resultNote.value,
  }
}

async function submit(): Promise<void> {
  if (task.value === null) {
    return
  }
  saving.value = true
  errorText.value = ''
  successText.value = ''
  try {
    if (task.value.taskType === 'OTHER_TASK') {
      await productionService.verifyOther({
        taskBusinessId: task.value.taskBusinessId,
        completedQuantity:
          task.value.otherTask?.plannedQuantity == null ? null : Number(otherCompleted.value),
        unfinishedQuantity:
          task.value.otherTask?.plannedQuantity == null ? null : Number(otherUnfinished.value),
        resultNote: resultNote.value === '' ? null : resultNote.value,
      })
    } else {
      await productionService.verify(items.value.map(toVerifyInput))
    }
    successText.value = '核验已提交；数量与事实以服务端结果为准'
    ElMessage.success(successText.value)
    task.value = await productionService.task(task.value.taskBusinessId)
    items.value = task.value.items.map(defaults)
  } catch (error) {
    errorText.value = userMessageOf(error, '核验失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="verify-page">
    <h1>任务核验</h1>
    <el-alert v-if="taskId === ''" type="info" :closable="false" title="请从排班或任务详情进入核验" />
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert v-if="successText" type="success" :closable="false" :title="successText" class="alert" />

    <template v-if="task">
      <el-descriptions :column="3" border class="summary">
        <el-descriptions-item label="任务">{{ task.taskNo }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ task.taskType }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ task.status }}</el-descriptions-item>
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
        <el-table :data="items" border>
          <el-table-column prop="taskItemBusinessId" label="明细" min-width="180" />
          <el-table-column prop="plannedQuantity" label="计划" width="80" />
          <el-table-column label="未完成" width="110">
            <template #default="{ row }"><el-input v-model.number="row.unfinishedQuantity" type="number" /></template>
          </el-table-column>
          <el-table-column label="合格" width="110">
            <template #default="{ row }"><el-input v-model.number="row.qualifiedQuantity" type="number" /></template>
          </el-table-column>
          <el-table-column label="返工/修补" width="120">
            <template #default="{ row }"><el-input v-model.number="row.reworkQuantity" type="number" /></template>
          </el-table-column>
          <el-table-column label="线上报废" width="120">
            <template #default="{ row }"><el-input v-model.number="row.scrappedQuantity" type="number" /></template>
          </el-table-column>
          <el-table-column label="报废去向" width="170">
            <template #default="{ row }">
              <el-select v-model="row.destination">
                <el-option label="实际销毁" value="DESTROYED" />
                <el-option label="转线下库存" value="TRANSFERRED_OFFLINE_INVENTORY" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
        <el-form label-width="90px" class="form">
          <el-form-item label="核验说明">
            <el-input v-model="resultNote" type="textarea" :rows="2" />
          </el-form-item>
        </el-form>
        <p class="hint">
          报废数量只填写一次，去向另行选择；返工与报废共同构成缺陷事实，不会重复增加完成量。
        </p>
      </template>

      <el-button type="primary" :loading="saving" @click="submit">提交最终核验</el-button>
    </template>
  </section>
</template>

<style scoped>
.verify-page {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 20px;
}

h1 {
  margin: 0 0 12px;
  font-size: 18px;
}

.summary {
  margin-bottom: 16px;
}

.form {
  max-width: 620px;
}

.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.alert {
  margin-bottom: 12px;
}
</style>
