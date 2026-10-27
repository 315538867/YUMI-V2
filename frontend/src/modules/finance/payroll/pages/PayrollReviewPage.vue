<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { payrollService } from '@/modules/finance/payroll/services/payrollService'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

const resultId = ref('')
const errorText = ref('')
const successText = ref('')

async function review(): Promise<void> {
  if (resultId.value.trim() === '') {
    errorText.value = '请输入工资结果标识'
    return
  }
  try {
    await payrollService.review(resultId.value.trim())
    successText.value = '已生成只读复核运行；原核定、应付与已付不变'
    ElMessage.success(successText.value)
  } catch (error) {
    errorText.value = userMessageOf(error, '工资复核失败')
  }
}
</script>

<template>
  <WorkspacePage class="review-page">
    <h1>已核定工资只读复核</h1>
    <el-alert
      type="info"
      :closable="false"
      title="复核运行只供查看迟到来源与配置变化，不能用于再次确认工资。"
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
    <el-form inline>
      <el-form-item label="工资结果">
        <el-input v-model="resultId" class="input" />
      </el-form-item>
      <el-button type="primary" @click="review">生成复核运行</el-button>
    </el-form>
  </WorkspacePage>
</template>

<style scoped>
h1 {
  margin: 0 0 12px;
  font-size: 18px;
}
.input {
  width: 300px;
}
.alert {
  margin-bottom: 12px;
}
</style>
