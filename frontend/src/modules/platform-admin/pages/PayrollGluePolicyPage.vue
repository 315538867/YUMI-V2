<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { isErrorCode, userMessageOf } from '@/platform/services/errorMessage'
import type { GluePolicyView } from '@/modules/finance/payroll/contracts'
import { payrollService } from '@/modules/finance/payroll/services/payrollService'

const month = ref(new Date().toISOString().slice(0, 7))
const policy = ref<GluePolicyView | null>(null)
const policyMissing = ref(false)
const errorText = ref('')
const successText = ref('')
const form = reactive({ exemptWeightGrams: '300.0000', reasonText: '', expectedGuardVersion: 0 })

async function load(): Promise<void> {
  policyMissing.value = false
  errorText.value = ''
  try {
    policy.value = await payrollService.gluePolicy(month.value)
    form.exemptWeightGrams = policy.value.exemptWeightGrams
  } catch (error) {
    policy.value = null
    if (isErrorCode(error, 'PAYROLL_GLUE_POLICY_MISSING')) {
      policyMissing.value = true
    } else {
      errorText.value = userMessageOf(error, '胶水配置加载失败')
    }
  }
}

async function publish(): Promise<void> {
  try {
    policy.value = await payrollService.publishGluePolicy({
      effectiveMonth: `${month.value}-01`,
      exemptWeightGrams: form.exemptWeightGrams,
      reasonText: form.reasonText,
      expectedGuardVersion: Number(form.expectedGuardVersion),
    })
    policyMissing.value = false
    successText.value = `已发布 v${policy.value.version}；历史核定不变`
    ElMessage.success(successText.value)
  } catch (error) {
    errorText.value = userMessageOf(error, '胶水配置发布失败')
  }
}

onMounted(load)
</script>

<template>
  <section class="glue-policy-page">
    <h1>胶水减免设置</h1>
    <el-alert type="info" :closable="false" title="配置按生效月追加版本；修订不会重算已核定工资。" class="alert" />
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert v-if="successText" type="success" :closable="false" :title="successText" class="alert" />
    <el-alert
      v-if="policyMissing"
      type="info"
      :closable="false"
      title="该月尚未配置胶水减免；填写下方表单并发布首个版本。"
      class="alert"
    />
    <el-form inline>
      <el-form-item label="生效月">
        <el-input v-model="month" type="month" class="month" @change="load" />
      </el-form-item>
      <el-form-item label="每人免扣重量">
        <el-input v-model="form.exemptWeightGrams" class="weight" />
      </el-form-item>
      <el-form-item label="原因">
        <el-input v-model="form.reasonText" class="reason" />
      </el-form-item>
      <el-button type="primary" @click="publish">发布版本</el-button>
    </el-form>
    <el-descriptions v-if="policy" :column="3" border class="current">
      <el-descriptions-item label="当前版本">v{{ policy.version }}</el-descriptions-item>
      <el-descriptions-item label="生效月">{{ policy.effectiveMonth }}</el-descriptions-item>
      <el-descriptions-item label="免扣重量">{{ policy.exemptWeightGrams }} 克/人/月</el-descriptions-item>
    </el-descriptions>
  </section>
</template>

<style scoped>
.glue-policy-page { background: #fff; border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 20px; }
h1 { margin: 0 0 12px; font-size: 18px; }
.month { width: 150px; }
.weight { width: 140px; }
.reason { width: 220px; }
.current { margin-top: 16px; }
.alert { margin-bottom: 12px; }
</style>
