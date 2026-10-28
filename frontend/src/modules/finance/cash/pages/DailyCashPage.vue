<script setup lang="ts">
import { reactive, ref } from 'vue'
import { cashService } from '@/modules/finance/cash/services/cashService'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import CodeLabel from '@/shared/components/CodeLabel.vue'
import MoneyText from '@/shared/components/MoneyText.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'
import PageShell from '@/shared/components/PageShell.vue'

const form = reactive({
  direction: 'IN' as 'IN' | 'OUT',
  categoryCode: 'DAILY_MISC',
  businessDate: new Date().toISOString().slice(0, 10),
  amount: '',
  description: '',
  countAsExpense: false,
})
const { loading: saving, run: runAction } = useAsyncAction()
const errorText = ref('')
const successText = ref('')
const previewVisible = ref(false)

function requestPreview(): void {
  if (form.amount.trim() === '') {
    errorText.value = '请填写金额'
    return
  }
  errorText.value = ''
  previewVisible.value = true
}

async function submit(): Promise<void> {
  await runAction(() => cashService.recordDailyCash({ ...form }), {
    success: '日常收支已入账',
    fallback: '日常收支入账失败',
    onSuccess: (result) => {
      successText.value = `已入账：${result.dailyRecordBusinessId}；来源与费用只在同一事务产生`
      form.amount = ''
      form.description = ''
    },
  })
}
</script>

<template>
  <PageShell title="日常经营收支">
    <WorkspacePage class="daily-page">
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
      <el-form label-width="120px" class="form">
        <el-form-item label="方向">
          <el-radio-group v-model="form.direction">
            <el-radio value="IN">收入</el-radio>
            <el-radio value="OUT">支出</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="form.categoryCode" filterable allow-create default-first-option>
            <el-option label="日常杂项" value="DAILY_MISC" />
            <el-option label="日常收入" value="DAILY_INCOME" />
            <el-option label="日常支出" value="DAILY_EXPENSE" />
          </el-select>
        </el-form-item>
        <el-form-item label="业务日期">
          <el-date-picker v-model="form.businessDate" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="金额"><el-input v-model="form.amount" /></el-form-item>
        <el-form-item label="说明"
          ><el-input v-model="form.description" type="textarea" :rows="2"
        /></el-form-item>
        <el-form-item label="计入经营费用">
          <el-switch v-model="form.countAsExpense" :disabled="form.direction === 'IN'" />
        </el-form-item>
        <el-button type="primary" @click="requestPreview">登记收支</el-button>
      </el-form>
      <p class="yumi-hint">业务日期决定归属月份；现金与成本分别保存，不混合成一个总额。</p>

      <el-dialog v-model="previewVisible" title="核对日常收支" width="460px">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="方向">
            {{ form.direction === 'IN' ? '收入' : '支出' }}
          </el-descriptions-item>
          <el-descriptions-item label="分类">
            <CodeLabel
              category="finance.dailyCashCategory"
              :code="form.categoryCode"
              fallback="其他分类"
            />
          </el-descriptions-item>
          <el-descriptions-item label="业务日期">{{ form.businessDate }}</el-descriptions-item>
          <el-descriptions-item label="金额"
            ><MoneyText :value="form.amount" /> CNY</el-descriptions-item
          >
          <el-descriptions-item label="说明">{{ form.description || '—' }}</el-descriptions-item>
          <el-descriptions-item label="计入经营费用">
            {{ form.countAsExpense ? '是' : '否' }}
          </el-descriptions-item>
        </el-descriptions>
        <template #footer>
          <el-button @click="previewVisible = false">返回修改</el-button>
          <el-button type="primary" :loading="saving" @click="submit">确认入账</el-button>
        </template>
      </el-dialog>
    </WorkspacePage>
  </PageShell>
</template>

<style scoped>
.form {
  max-width: 560px;
}
</style>
