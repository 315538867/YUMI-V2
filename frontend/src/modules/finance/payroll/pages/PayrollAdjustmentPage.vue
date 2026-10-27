<script setup lang="ts">
import { computed, reactive } from 'vue'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { payrollService } from '@/modules/finance/payroll/services/payrollService'

const validationErrors = reactive<Record<string, string>>({})
const { loading: saving, fieldError, otherFieldErrors, run } = useAsyncAction()
const formAlert = computed(() =>
  otherFieldErrors('resultBusinessId', 'amountDelta', 'reasonText', 'referenceRunBusinessId').join(
    '；',
  ),
)
const form = reactive({
  resultBusinessId: '',
  amountDelta: '',
  reasonText: '',
  referenceRunBusinessId: '',
})

function validate(): boolean {
  for (const key of Object.keys(validationErrors)) {
    delete validationErrors[key]
  }
  if (form.resultBusinessId.trim() === '') {
    validationErrors.resultBusinessId = '请填写工资结果'
  }
  if (!/^-?\d+(\.\d{1,2})?$/.test(form.amountDelta) || Number(form.amountDelta) === 0) {
    validationErrors.amountDelta = '差额必须为非 0 且最多两位小数'
  }
  if (form.reasonText.trim() === '') {
    validationErrors.reasonText = '请填写调整原因'
  }
  return Object.keys(validationErrors).length === 0
}

async function submit(): Promise<void> {
  if (!validate()) {
    return
  }
  await run(
    () =>
      payrollService.adjust({
        resultBusinessId: form.resultBusinessId.trim(),
        amountDelta: form.amountDelta,
        reasonText: form.reasonText,
        referenceRunBusinessId:
          form.referenceRunBusinessId.trim() === '' ? null : form.referenceRunBusinessId.trim(),
      }),
    {
      success: '人工差额已追加；不产生第二笔现金',
      fallback: '工资调整失败',
    },
  )
}
</script>

<template>
  <section class="adjust-page">
    <h1>工资人工调整</h1>
    <el-alert
      type="info"
      :closable="false"
      title="调整只改变应付与人工成本，不改原核定金额，也不产生现金。"
      class="alert"
    />
    <el-form label-width="120px" class="form">
      <el-alert v-if="formAlert" type="error" :closable="false" :title="formAlert" class="alert" />
      <el-form-item
        label="工资结果"
        :error="validationErrors.resultBusinessId || fieldError('resultBusinessId')"
      >
        <el-input v-model="form.resultBusinessId" />
      </el-form-item>
      <el-form-item label="差额" :error="validationErrors.amountDelta || fieldError('amountDelta')">
        <el-input v-model="form.amountDelta" placeholder="可带负号" />
      </el-form-item>
      <el-form-item label="原因" :error="validationErrors.reasonText || fieldError('reasonText')">
        <el-input v-model="form.reasonText" type="textarea" :rows="2" />
      </el-form-item>
      <el-form-item label="复核运行（可选）" :error="fieldError('referenceRunBusinessId')">
        <el-input v-model="form.referenceRunBusinessId" />
      </el-form-item>
      <el-button type="primary" :loading="saving" @click="submit">提交调整</el-button>
    </el-form>
  </section>
</template>

<style scoped>
.adjust-page {
  background: var(--yumi-surface);
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
.alert {
  margin-bottom: 12px;
}
</style>
