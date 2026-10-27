<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { costLedgerService } from '@/modules/finance/costledger/services/costLedgerService'

const errorText = ref('')
const successText = ref('')
const form = reactive({ entryId: '', newAmount: '', reasonText: '' })

async function submit(): Promise<void> {
  if (form.entryId.trim() === '' || form.reasonText.trim() === '') {
    errorText.value = '请填写成本事实与更正原因'
    return
  }
  try {
    await costLedgerService.adjust(form.entryId.trim(), form.newAmount, form.reasonText)
    successText.value = '已追加反向事实与替代成本，原金额保留可追溯'
    ElMessage.success(successText.value)
  } catch (error) {
    errorText.value = userMessageOf(error, '成本更正失败')
  }
}
</script>

<template>
  <section class="adjust-page">
    <h1>成本更正</h1>
    <el-alert
      type="info"
      :closable="false"
      title="更正不覆盖历史来源；原金额整笔反向，再写入新金额。"
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
    <el-form label-width="110px" class="form">
      <el-form-item label="成本事实"><el-input v-model="form.entryId" /></el-form-item>
      <el-form-item label="新金额"><el-input v-model="form.newAmount" /></el-form-item>
      <el-form-item label="更正原因"
        ><el-input v-model="form.reasonText" type="textarea" :rows="2"
      /></el-form-item>
      <el-button type="primary" @click="submit">提交更正</el-button>
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
