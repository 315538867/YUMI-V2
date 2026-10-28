<script setup lang="ts">
import { ref } from 'vue'
import { payrollService } from '@/modules/finance/payroll/services/payrollService'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'
import PageShell from '@/shared/components/PageShell.vue'

const resultId = ref('')
const { loading: saving, run: runAction } = useAsyncAction()
const errorText = ref('')
const successText = ref('')

async function review(): Promise<void> {
  if (resultId.value.trim() === '') {
    errorText.value = '请输入工资结果标识'
    return
  }
  await runAction(() => payrollService.review(resultId.value.trim()), {
    success: '已生成只读复核运行；原核定、应付与已付不变',
    fallback: '工资复核失败',
    onSuccess: () => {
      successText.value = '已生成只读复核运行；原核定、应付与已付不变'
    },
  })
}
</script>

<template>
  <PageShell title="已核定工资只读复核">
    <WorkspacePage class="review-page">
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
        <el-button type="primary" :loading="saving" @click="review">生成复核运行</el-button>
      </el-form>
    </WorkspacePage>
  </PageShell>
</template>

<style scoped>
.input {
  width: 300px;
}
</style>
