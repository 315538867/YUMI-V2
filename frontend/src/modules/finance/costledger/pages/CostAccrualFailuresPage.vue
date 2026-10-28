<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { CostFailureView } from '@/modules/finance/costledger/contracts'
import { costLedgerService } from '@/modules/finance/costledger/services/costLedgerService'
import CodeTag from '@/shared/components/CodeTag.vue'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import ListPage from '@/shared/components/ListPage.vue'

const { loading, errorText, run } = useAsyncLoad()

const failures = ref<CostFailureView[]>([])
const successText = ref('')
const { loading: retrying, run: runAction } = useAsyncAction()
const dates = reactive({
  startDate: new Date().toISOString().slice(0, 8) + '01',
  endDate: new Date().toISOString().slice(0, 10),
})

async function load(): Promise<void> {
  await run(async () => {
    failures.value = await costLedgerService.failures()
  }, '归集失败加载失败')
}

async function retry(failure: CostFailureView): Promise<void> {
  await runAction(
    () => costLedgerService.retryFailure(failure.failureBusinessId, dates.startDate, dates.endDate),
    {
      success: '归集重试完成；不重复入账',
      fallback: '归集重试失败',
      onSuccess: async (result) => {
        successText.value = `重试完成：归集 ${result.accruedCount} 条；不重复入账`
        await load()
      },
    },
  )
}

onMounted(load)
</script>

<template>
  <ListPage
    class="failures-page"
    title="成本归集失败"
    :heading="false"
    panel-title="成本归集失败"
    panel-hint="失败来源保留待恢复；重试沿用原命令"
    :loading="loading"
    :error="errorText"
    @retry="load"
  >
    <template #filters>
      <el-date-picker v-model="dates.startDate" type="date" value-format="YYYY-MM-DD" />
      <el-date-picker v-model="dates.endDate" type="date" value-format="YYYY-MM-DD" />
    </template>
    <template #notice>
      <el-alert v-if="successText" type="success" :closable="false" :title="successText" />
    </template>
    <el-table :data="failures" border empty-text="暂无待恢复的归集失败">
      <el-table-column label="来源模块" width="140">
        <template #default="{ row }"
          ><CodeTag category="finance.costSourceModule" :code="row.sourceModule"
        /></template>
      </el-table-column>
      <el-table-column label="来源类型" width="170">
        <template #default="{ row }"
          ><CodeTag category="finance.costSourceType" :code="row.sourceType"
        /></template>
      </el-table-column>
      <el-table-column prop="sourceBusinessId" label="来源标识" min-width="200" />
      <el-table-column prop="lastError" label="失败原因" min-width="220" />
      <el-table-column prop="attempts" label="尝试" width="80" />
      <el-table-column label="操作" width="100">
        <template #default="{ row }">
          <el-button link type="primary" :loading="retrying" @click="retry(row)">重试</el-button>
        </template>
      </el-table-column>
    </el-table>
    <template #footer>
      <p class="yumi-hint">失败不推进来源水位；重试沿用原命令，不重复归集。</p>
    </template>
  </ListPage>
</template>
