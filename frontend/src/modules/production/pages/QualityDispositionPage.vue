<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { DispositionView, ResponsibilityType } from '@/modules/production/contracts'
import { productionService } from '@/modules/production/services/productionService'
import CodeTag from '@/shared/components/CodeTag.vue'

const taskItemId = ref('')
const dispositions = ref<DispositionView[]>([])
const errorText = ref('')
const dialogVisible = ref(false)
const form = reactive({
  dispositionFactBusinessId: '',
  responsibilityType: 'STUDIO' as ResponsibilityType,
  responsibleSubjectBusinessId: '',
  waivedReason: '',
  reasonText: '',
  expectedRowVersion: 1,
})

async function load(): Promise<void> {
  if (taskItemId.value.trim() === '') {
    errorText.value = '请输入任务明细标识'
    return
  }
  errorText.value = ''
  try {
    dispositions.value = await productionService.dispositions(taskItemId.value.trim())
  } catch (error) {
    errorText.value = userMessageOf(error, '处置记录加载失败')
  }
}

function openCorrection(dispositionFactBusinessId: string): void {
  form.dispositionFactBusinessId = dispositionFactBusinessId
  form.responsibilityType = 'STUDIO'
  form.responsibleSubjectBusinessId = ''
  form.waivedReason = ''
  form.reasonText = ''
  form.expectedRowVersion = 1
  dialogVisible.value = true
}

async function correct(): Promise<void> {
  try {
    await productionService.correctResponsibility({
      dispositionFactBusinessId: form.dispositionFactBusinessId,
      responsibilityType: form.responsibilityType,
      responsibleSubjectBusinessId:
        form.responsibilityType === 'WAIVED' ? null : form.responsibleSubjectBusinessId,
      waivedReason: form.responsibilityType === 'WAIVED' ? form.waivedReason : null,
      reasonText: form.reasonText,
      expectedRowVersion: Number(form.expectedRowVersion),
    })
    dialogVisible.value = false
    ElMessage.success('责任已更正')
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '责任更正失败')
  }
}

function destinationText(value: string): string {
  return (
    {
      DESTROYED: '实际销毁',
      TRANSFERRED_OFFLINE_INVENTORY: '转线下库存',
      IN_SYSTEM: '系统内',
    }[value] ?? value
  )
}
</script>

<template>
  <section class="disposition-page">
    <h1>质量处置与责任</h1>
    <div class="toolbar">
      <el-input v-model="taskItemId" placeholder="任务明细标识" class="input" @keyup.enter="load" />
      <el-button type="primary" @click="load">查询</el-button>
    </div>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-empty v-if="dispositions.length === 0" description="暂无处置记录或尚未查询" />
    <el-card v-for="group in dispositions" :key="group.taskItemBusinessId" class="card">
      <template #header>
        <strong>{{ group.taskItemBusinessId }}</strong>
        <span class="meta"> · 来源 {{ group.productionSourceBusinessId }}</span>
      </template>
      <div v-for="defect in group.defects" :key="defect.defectFactBusinessId" class="defect">
        <p>
          <el-tag>{{ defect.defectReasonName }}</el-tag>
          数量 {{ defect.quantity }}（返工 {{ defect.reworkQuantity }} / 报废
          {{ defect.scrappedQuantity }}）
        </p>
        <el-table :data="defect.dispositions" border size="small">
          <el-table-column prop="dispositionFactBusinessId" label="处置标识" min-width="200" />
          <el-table-column label="处置" width="140">
            <template #default="{ row }"
              ><CodeTag category="production.defectDispositionType" :code="row.dispositionType"
            /></template>
          </el-table-column>
          <el-table-column label="去向" width="130">
            <template #default="{ row }">{{ destinationText(row.physicalDestination) }}</template>
          </el-table-column>
          <el-table-column prop="quantity" label="数量" width="80" />
          <el-table-column label="责任" width="120">
            <template #default="{ row }"
              ><CodeTag category="production.responsibilityType" :code="row.responsibilityType"
            /></template>
          </el-table-column>
          <el-table-column prop="responsibleSubjectBusinessId" label="责任主体" min-width="160" />
          <el-table-column prop="waivedReason" label="免除原因" min-width="160" />
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <el-button link type="primary" @click="openCorrection(row.dispositionFactBusinessId)">
                更正责任
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" title="责任更正" width="460px">
      <el-form label-width="100px">
        <el-form-item label="责任类型">
          <el-select v-model="form.responsibilityType">
            <el-option label="员工" value="EMPLOYEE" />
            <el-option label="小组" value="TEAM" />
            <el-option label="工作室" value="STUDIO" />
            <el-option label="客户" value="CUSTOMER" />
            <el-option label="免除" value="WAIVED" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.responsibilityType !== 'WAIVED'" label="责任主体">
          <el-input v-model="form.responsibleSubjectBusinessId" />
        </el-form-item>
        <el-form-item v-else label="免除原因">
          <el-input v-model="form.waivedReason" />
        </el-form-item>
        <el-form-item label="更正原因">
          <el-input v-model="form.reasonText" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="期望行版本">
          <el-input v-model.number="form.expectedRowVersion" type="number" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="correct">提交更正</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.disposition-page {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 20px;
}

h1 {
  margin: 0 0 12px;
  font-size: 18px;
}

.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.input {
  width: 280px;
}

.card {
  margin-bottom: 12px;
}

.defect {
  margin-bottom: 12px;
}

.meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.alert {
  margin-bottom: 12px;
}
</style>
