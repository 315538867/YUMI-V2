<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useListQuery } from '@/platform/composables/useListQuery'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { AfterCommitTask } from '@/modules/platform-admin/services/administrationService'
import { administrationService } from '@/modules/platform-admin/services/administrationService'
import CodeTag from '@/shared/components/CodeTag.vue'
import CodeLabel from '@/shared/components/CodeLabel.vue'
import ListPage from '@/shared/components/ListPage.vue'

const { loading, errorText, run } = useAsyncLoad()
const { loading: saving, run: runAction } = useAsyncAction()

const tasks = ref<AfterCommitTask[]>([])
const { filters, search, reset } = useListQuery(
  { status: 'DEAD' },
  { paged: false, onSearch: () => void load() },
)
const successText = ref('')
const abandonVisible = ref(false)
const active = ref<AfterCommitTask | null>(null)
const form = reactive({ reason: '' })

async function load(): Promise<void> {
  await run(async () => {
    tasks.value = await administrationService.tasks(filters.status)
  }, '提交后任务加载失败')
}

async function retry(task: AfterCommitTask): Promise<void> {
  await runAction(() => administrationService.retryTask(task.taskBusinessId), {
    success: '已请求重试；沿用原任务身份与去重键',
    fallback: '任务重试失败',
    onSuccess: async () => {
      successText.value = '已请求重试；沿用原任务身份与去重键'
      await load()
    },
  })
}

function openAbandon(task: AfterCommitTask): void {
  active.value = task
  form.reason = ''
  abandonVisible.value = true
}

async function abandon(): Promise<void> {
  if (active.value === null || form.reason.trim() === '') {
    errorText.value = '放弃任务必须填写原因'
    return
  }
  await runAction(
    () => administrationService.abandonTask(active.value!.taskBusinessId, form.reason),
    {
      success: '任务已标记终止；原业务事实不变',
      fallback: '任务放弃失败',
      onSuccess: async () => {
        abandonVisible.value = false
        successText.value = '任务已标记终止；原业务事实不变'
        await load()
      },
    },
  )
}

void load()
</script>

<template>
  <ListPage
    class="tasks-page"
    title="提交后任务"
    :loading="loading"
    :error="errorText"
    :empty="!loading && errorText === '' && tasks.length === 0"
    empty-text="该状态下没有任务"
    @retry="load"
  >
    <template #filters>
      <el-select v-model="filters.status" class="status" aria-label="任务状态" @change="search">
        <el-option label="待执行" value="PENDING" />
        <el-option label="等待重试" value="RETRY_WAIT" />
        <el-option label="需人工处理" value="DEAD" />
        <el-option label="已完成" value="SUCCEEDED" />
      </el-select>
      <el-button @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </template>
    <template #notice>
      <el-alert v-if="successText" type="success" :closable="false" :title="successText" />
    </template>
    <el-table :data="tasks" border empty-text="暂无任务">
      <el-table-column label="任务类型" width="170">
        <template #default="{ row }"
          ><CodeLabel
            category="platform.afterCommitTaskType"
            :code="row.taskType"
            fallback="其他任务"
        /></template>
      </el-table-column>
      <el-table-column prop="deduplicationKey" label="去重键" min-width="220" />
      <el-table-column label="状态" width="120">
        <template #default="{ row }"
          ><CodeTag category="platform.afterCommitTaskStatus" :code="row.status"
        /></template>
      </el-table-column>
      <el-table-column prop="attempts" label="尝试" width="80" />
      <el-table-column prop="lastError" label="失败原因" min-width="240" />
      <el-table-column label="来源" min-width="220">
        <template #default="{ row }">{{ JSON.stringify(row.payload) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <el-button link type="primary" @click="retry(row)">重试</el-button>
          <el-button link type="danger" @click="openAbandon(row)">放弃</el-button>
        </template>
      </el-table-column>
    </el-table>
  </ListPage>

  <el-dialog v-model="abandonVisible" title="放弃任务" width="420px">
    <el-form label-width="92px">
      <el-form-item label="原因"
        ><el-input v-model="form.reason" type="textarea" :rows="2"
      /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="abandonVisible = false">取消</el-button>
      <el-button type="danger" :loading="saving" @click="abandon">确认放弃</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.status {
  width: 160px;
}
</style>
