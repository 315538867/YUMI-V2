<script setup lang="ts">
import { reactive, ref } from 'vue'
import { costLedgerService } from '@/modules/finance/costledger/services/costLedgerService'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

const { loading: saving, run: runAction } = useAsyncAction()
const errorText = ref('')
const successText = ref('')
const form = reactive({ entryId: '', newAmount: '', reasonText: '' })

async function submit(): Promise<void> {
  if (form.entryId.trim() === '' || form.reasonText.trim() === '') {
    errorText.value = '请填写成本事实与更正原因'
    return
  }
  await runAction(
    () => costLedgerService.adjust(form.entryId.trim(), form.newAmount, form.reasonText),
    {
      success: '已追加反向事实与替代成本，原金额保留可追溯',
      fallback: '成本更正失败',
      onSuccess: () => {
        successText.value = '已追加反向事实与替代成本，原金额保留可追溯'
      },
    },
  )
}
</script>

<template>
  <WorkspacePage class="adjust-page" title="成本更正" hint="原金额整笔反向，再写入新金额">
    <el-alert
      type="info"
      :closable="false"
      title="更正不覆盖历史来源；原金额整笔反向，再写入新金额。"
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
    <el-form label-width="110px" class="form">
      <el-form-item label="成本事实"><el-input v-model="form.entryId" /></el-form-item>
      <el-form-item label="新金额"><el-input v-model="form.newAmount" /></el-form-item>
      <el-form-item label="更正原因"
        ><el-input v-model="form.reasonText" type="textarea" :rows="2"
      /></el-form-item>
      <el-button type="primary" :loading="saving" @click="submit">提交更正</el-button>
    </el-form>
  </WorkspacePage>
</template>

<style scoped>
.form {
  max-width: 560px;
}
</style>
