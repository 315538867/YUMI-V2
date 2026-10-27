<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { CostFailureView } from '@/modules/finance/costledger/contracts'
import { costLedgerService } from '@/modules/finance/costledger/services/costLedgerService'
import CodeTag from '@/shared/components/CodeTag.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

const failures = ref<CostFailureView[]>([])
const errorText = ref('')
const successText = ref('')
const dates = reactive({
  startDate: new Date().toISOString().slice(0, 8) + '01',
  endDate: new Date().toISOString().slice(0, 10),
})

async function load(): Promise<void> {
  try {
    failures.value = await costLedgerService.failures()
  } catch (error) {
    errorText.value = userMessageOf(error, '归集失败加载失败')
  }
}

async function retry(failure: CostFailureView): Promise<void> {
  try {
    const result = await costLedgerService.retryFailure(
      failure.failureBusinessId,
      dates.startDate,
      dates.endDate,
    )
    successText.value = `重试完成：归集 ${result.accruedCount} 条；不重复入账`
    ElMessage.success(successText.value)
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '归集重试失败')
  }
}

onMounted(load)
</script>

<template>
  <WorkspacePage class="failures-page">
    <h1>成本归集失败</h1>
    <div class="toolbar">
      <el-date-picker v-model="dates.startDate" type="date" value-format="YYYY-MM-DD" />
      <el-date-picker v-model="dates.endDate" type="date" value-format="YYYY-MM-DD" />
    </div>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert
      v-if="successText"
      type="success"
      :closable="false"
      :title="successText"
      class="alert"
    />
    <el-table :data="failures" border empty-text="暂无待恢复的归集失败">
      <el-table-column label="来源模块" width="140">
        <template #default="{ row }"
          ><CodeTag category="finance.costSourceModule" :code="row.sourceModule"
        /></template>
      </el-table-column>
      <el-table-column label="来源类型" width="170">
        <template #default="{ row }"
          ><CodeTag category="finance.costSourceType" :code="row.sourceType"
        /></template>
      </el-table-column>
      <el-table-column prop="sourceBusinessId" label="来源标识" min-width="200" />
      <el-table-column prop="lastError" label="失败原因" min-width="220" />
      <el-table-column prop="attempts" label="尝试" width="80" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="primary" @click="retry(row)">重试</el-button>
        </template>
      </el-table-column>
    </el-table>
    <p class="hint">失败不推进来源水位；重试沿用原命令，不重复归集。</p>
  </WorkspacePage>
</template>

<style scoped>
h1 {
  margin: 0 0 12px;
  font-size: 18px;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.alert {
  margin-bottom: 12px;
}
</style>
