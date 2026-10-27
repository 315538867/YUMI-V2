<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { cashService } from '@/modules/finance/cash/services/cashService'

const form = reactive({
  direction: 'IN' as 'IN' | 'OUT',
  categoryCode: 'DAILY_MISC',
  businessDate: new Date().toISOString().slice(0, 10),
  amount: '',
  description: '',
  countAsExpense: false,
})
const errorText = ref('')
const successText = ref('')

async function submit(): Promise<void> {
  if (form.amount.trim() === '') {
    errorText.value = '请填写金额'
    return
  }
  try {
    const result = await cashService.recordDailyCash({ ...form })
    successText.value = `已入账：${result.dailyRecordBusinessId}；来源与费用只在同一事务产生`
    ElMessage.success('日常收支已入账')
    form.amount = ''
    form.description = ''
  } catch (error) {
    errorText.value = userMessageOf(error, '日常收支入账失败')
  }
}
</script>

<template>
  <section class="daily-page">
    <h1>日常经营收支</h1>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert
      v-if="successText"
      type="success"
      :closable="false"
      :title="successText"
      class="alert"
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
      <el-button type="primary" @click="submit">登记收支</el-button>
    </el-form>
    <p class="hint">业务日期决定归属月份；现金与成本分别保存，不混合成一个总额。</p>
  </section>
</template>

<style scoped>
.daily-page {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 20px;
}
h1 {
  margin: 0 0 12px;
  font-size: 18px;
}
.form {
  max-width: 560px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.alert {
  margin-bottom: 12px;
}
</style>
