<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { isErrorCode } from '@/platform/services/errorMessage'
import type { GluePolicyView } from '@/modules/finance/payroll/contracts'
import { payrollService } from '@/modules/finance/payroll/services/payrollService'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'
import PageShell from '@/shared/components/PageShell.vue'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'

const { errorText, run } = useAsyncLoad()
const { loading: saving, run: runAction } = useAsyncAction()

const month = ref(new Date().toISOString().slice(0, 7))
const policy = ref<GluePolicyView | null>(null)
const policyMissing = ref(false)
const successText = ref('')
const form = reactive({ exemptWeightGrams: '300.0000', reasonText: '', expectedGuardVersion: 0 })

async function load(): Promise<void> {
  policyMissing.value = false
  await run(
    async () => {
      policy.value = await payrollService.gluePolicy(month.value)
      form.exemptWeightGrams = policy.value.exemptWeightGrams
    },
    '胶水配置加载失败',
    {
      handled: (error) => {
        if (isErrorCode(error, 'PAYROLL_GLUE_POLICY_MISSING')) {
          policy.value = null
          policyMissing.value = true
          return true
        }
        return false
      },
    },
  )
}

async function publish(): Promise<void> {
  await runAction(
    () =>
      payrollService.publishGluePolicy({
        effectiveMonth: `${month.value}-01`,
        exemptWeightGrams: form.exemptWeightGrams,
        reasonText: form.reasonText,
        expectedGuardVersion: Number(form.expectedGuardVersion),
      }),
    {
      success: '胶水配置已发布',
      fallback: '胶水配置发布失败',
      onSuccess: (published) => {
        policy.value = published
        policyMissing.value = false
        successText.value = `已发布 v${published.version}；历史核定不变`
      },
    },
  )
}

onMounted(load)
</script>

<template>
  <PageShell title="胶水减免设置">
    <WorkspacePage class="glue-policy-page">
      <el-alert
        type="info"
        :closable="false"
        title="配置按生效月追加版本；修订不会重算已核定工资。"
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
      <el-alert
        v-if="policyMissing"
        type="info"
        :closable="false"
        title="该月尚未配置胶水减免；填写下方表单并发布首个版本。"
        class="yumi-alert"
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
        <el-button type="primary" :loading="saving" @click="publish">发布版本</el-button>
      </el-form>
      <el-descriptions v-if="policy" :column="3" border class="current">
        <el-descriptions-item label="当前版本">v{{ policy.version }}</el-descriptions-item>
        <el-descriptions-item label="生效月">{{ policy.effectiveMonth }}</el-descriptions-item>
        <el-descriptions-item label="免扣重量"
          >{{ policy.exemptWeightGrams }} 克/人/月</el-descriptions-item
        >
      </el-descriptions>
    </WorkspacePage>
  </PageShell>
</template>

<style scoped>
.month {
  width: 150px;
}
.weight {
  width: 140px;
}
.reason {
  width: 220px;
}
.current {
  margin-top: var(--yumi-space-4);
}
</style>
