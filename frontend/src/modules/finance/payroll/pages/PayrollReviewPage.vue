<script setup lang="ts">
import { ref } from 'vue'
import type { PayrollReviewView } from '@/modules/finance/payroll/contracts'
import { payrollService } from '@/modules/finance/payroll/services/payrollService'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import CodeLabel from '@/shared/components/CodeLabel.vue'
import DateTimeText from '@/shared/components/DateTimeText.vue'
import MoneyText from '@/shared/components/MoneyText.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

const resultId = ref('')
const review = ref<PayrollReviewView | null>(null)
const { loading: saving, run: runAction } = useAsyncAction()
const errorText = ref('')
const successText = ref('')

async function runReview(): Promise<void> {
  if (resultId.value.trim() === '') {
    errorText.value = '请输入工资结果标识'
    return
  }
  errorText.value = ''
  successText.value = ''
  await runAction(() => payrollService.review(resultId.value.trim()), {
    success: '已生成只读复核运行；原核定、应付与已付不变',
    fallback: '工资复核失败',
    onSuccess: async (ref) => {
      const created = ref as { reviewRunBusinessId: string }
      review.value = await payrollService.reviewResult(created.reviewRunBusinessId)
      successText.value = '已生成只读复核运行；原核定、应付与已付不变'
    },
  })
}

function lineStatus(line: PayrollReviewView['lines'][number]): string {
  if (line.incomplete) return '依据缺失'
  if (line.estimated) return '预计分摊'
  if (!line.occurred) return '未发生'
  return '已发生'
}
</script>

<template>
  <WorkspacePage
    class="review-page"
    title="已核定工资只读复核"
    hint="查看迟到来源与配置变化，不再次确认工资"
  >
    <el-alert
      type="info"
      :closable="false"
      title="复核运行只供查看迟到来源与配置变化，不能用于再次确认工资。"
      class="yumi-alert"
    />
    <el-alert
      v-if="errorText"
      type="error"
      :closable="false"
      :title="errorText"
      class="yumi-alert"
    />
    <el-alert
      v-if="successText"
      type="success"
      :closable="false"
      :title="successText"
      class="yumi-alert"
    />
    <el-form inline>
      <el-form-item label="工资结果">
        <el-input v-model="resultId" class="input" />
      </el-form-item>
      <el-button type="primary" :loading="saving" @click="runReview">生成并查看复核</el-button>
    </el-form>

    <template v-if="review">
      <el-descriptions :column="3" border class="summary">
        <el-descriptions-item label="复核运行">{{
          review.reviewRunBusinessId
        }}</el-descriptions-item>
        <el-descriptions-item label="工资期间">{{ review.periodBusinessId }}</el-descriptions-item>
        <el-descriptions-item label="员工">{{ review.employeeBusinessId }}</el-descriptions-item>
        <el-descriptions-item label="参考指纹"
          >{{ review.fingerprint.slice(0, 16) }}…</el-descriptions-item
        >
        <el-descriptions-item label="生成时间">
          <DateTimeText :value="review.generatedAt" />
        </el-descriptions-item>
        <el-descriptions-item label="参考行">{{ review.lines.length }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="review.lines" border empty-text="本次复核没有参考行">
        <el-table-column label="成本项" min-width="170">
          <template #default="{ row }">
            <CodeLabel category="finance.costComponent" :code="row.componentCode" />
          </template>
        </el-table-column>
        <el-table-column prop="sourceBusinessId" label="来源" min-width="190" />
        <el-table-column prop="quantity" label="数量" width="90" />
        <el-table-column label="单价" width="120">
          <template #default="{ row }">
            <MoneyText :value="row.unitAmount" />
          </template>
        </el-table-column>
        <el-table-column label="参考金额" width="130">
          <template #default="{ row }">
            <MoneyText :value="row.referenceAmount" />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">{{ lineStatus(row) }}</template>
        </el-table-column>
        <el-table-column prop="note" label="说明" min-width="180" />
      </el-table>
    </template>
  </WorkspacePage>
</template>

<style scoped>
.input {
  width: 300px;
}

.summary {
  margin: var(--yumi-space-4) 0;
}
</style>
