<script setup lang="ts">
import { reactive } from 'vue'
import { useEditorForm } from '@/platform/composables/useEditorForm'
import { payrollService } from '@/modules/finance/payroll/services/payrollService'
import FormErrorAlert from '@/shared/components/FormErrorAlert.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'
import PageShell from '@/shared/components/PageShell.vue'

const { saving, validationErrors, formAlert, fieldError, validate, submit } = useEditorForm(
  ['resultBusinessId', 'amountDelta', 'reasonText', 'referenceRunBusinessId'],
  '工资结果已被其他操作修改，请重新打开后再调整',
)
const form = reactive({
  resultBusinessId: '',
  amountDelta: '',
  reasonText: '',
  referenceRunBusinessId: '',
})

function validateForm(): boolean {
  return validate([
    {
      field: 'resultBusinessId',
      invalid: form.resultBusinessId.trim() === '',
      message: '请填写工资结果',
    },
    {
      field: 'amountDelta',
      invalid: !/^-?\d+(\.\d{1,2})?$/.test(form.amountDelta) || Number(form.amountDelta) === 0,
      message: '差额必须为非 0 且最多两位小数',
    },
    {
      field: 'reasonText',
      invalid: form.reasonText.trim() === '',
      message: '请填写调整原因',
    },
  ])
}

async function submitAdjustment(): Promise<void> {
  if (!validateForm()) {
    return
  }
  await submit(
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
  <PageShell title="工资人工调整">
    <WorkspacePage class="adjust-page">
      <el-alert
        type="info"
        :closable="false"
        title="调整只改变应付与人工成本，不改原核定金额，也不产生现金。"
        class="yumi-alert"
      />
      <el-form label-width="120px" class="form">
        <FormErrorAlert :error="formAlert" class="yumi-alert" />
        <el-form-item
          label="工资结果"
          :error="validationErrors.resultBusinessId || fieldError('resultBusinessId')"
        >
          <el-input v-model="form.resultBusinessId" />
        </el-form-item>
        <el-form-item
          label="差额"
          :error="validationErrors.amountDelta || fieldError('amountDelta')"
        >
          <el-input v-model="form.amountDelta" placeholder="可带负号" />
        </el-form-item>
        <el-form-item label="原因" :error="validationErrors.reasonText || fieldError('reasonText')">
          <el-input v-model="form.reasonText" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="复核运行（可选）" :error="fieldError('referenceRunBusinessId')">
          <el-input v-model="form.referenceRunBusinessId" />
        </el-form-item>
        <el-button type="primary" :loading="saving" @click="submitAdjustment">提交调整</el-button>
      </el-form>
    </WorkspacePage>
  </PageShell>
</template>

<style scoped>
.form {
  max-width: 560px;
}
</style>
