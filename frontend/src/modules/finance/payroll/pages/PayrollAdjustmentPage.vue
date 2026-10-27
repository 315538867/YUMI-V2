<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { payrollService } from '@/modules/finance/payroll/services/payrollService'

const errorText = ref('')
const successText = ref('')
const form = reactive({
  resultBusinessId: '',
  amountDelta: '',
  reasonText: '',
  referenceRunBusinessId: '',
})

async function submit(): Promise<void> {
  if (form.resultBusinessId.trim() === '' || form.reasonText.trim() === '') {
    errorText.value = '请填写工资结果与调整原因'
    return
  }
  try {
    await payrollService.adjust({
      resultBusinessId: form.resultBusinessId.trim(),
      amountDelta: form.amountDelta,
      reasonText: form.reasonText,
      referenceRunBusinessId:
        form.referenceRunBusinessId.trim() === '' ? null : form.referenceRunBusinessId.trim(),
    })
    successText.value = '人工差额已追加；不产生第二笔现金'
    ElMessage.success(successText.value)
  } catch (error) {
    errorText.value = userMessageOf(error, '工资调整失败')
  }
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
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert
      v-if="successText"
      type="success"
      :closable="false"
      :title="successText"
      class="alert"
    />
    <el-form label-width="120px" class="form">
      <el-form-item label="工资结果"><el-input v-model="form.resultBusinessId" /></el-form-item>
      <el-form-item label="差额"
        ><el-input v-model="form.amountDelta" placeholder="可带负号"
      /></el-form-item>
      <el-form-item label="原因"
        ><el-input v-model="form.reasonText" type="textarea" :rows="2"
      /></el-form-item>
      <el-form-item label="复核运行（可选）"
        ><el-input v-model="form.referenceRunBusinessId"
      /></el-form-item>
      <el-button type="primary" @click="submit">提交调整</el-button>
    </el-form>
  </section>
</template>

<style scoped>
.adjust-page {
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
.alert {
  margin-bottom: 12px;
}
</style>
