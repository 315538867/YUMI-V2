<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { AfterCommitTask } from '@/modules/platform-admin/services/administrationService'
import { administrationService } from '@/modules/platform-admin/services/administrationService'
import CodeTag from '@/shared/components/CodeTag.vue'
import CodeLabel from '@/shared/components/CodeLabel.vue'

const status = ref('DEAD')
const tasks = ref<AfterCommitTask[]>([])
const errorText = ref('')
const successText = ref('')
const abandonVisible = ref(false)
const active = ref<AfterCommitTask | null>(null)
const form = reactive({ reason: '' })

async function load(): Promise<void> {
  try {
    tasks.value = await administrationService.tasks(status.value)
  } catch (error) {
    errorText.value = userMessageOf(error, '提交后任务加载失败')
  }
}

async function retry(task: AfterCommitTask): Promise<void> {
  try {
    await administrationService.retryTask(task.taskBusinessId)
    successText.value = '已请求重试；沿用原任务身份与去重键'
    ElMessage.success(successText.value)
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '任务重试失败')
  }
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
  try {
    await administrationService.abandonTask(active.value.taskBusinessId, form.reason)
    abandonVisible.value = false
    successText.value = '任务已标记终止；原业务事实不变'
    ElMessage.success(successText.value)
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '任务放弃失败')
  }
}

onMounted(load)
</script>

<template>
  <section class="tasks-page">
    <h1>提交后任务</h1>
    <div class="toolbar">
      <el-select v-model="status" class="status" aria-label="任务状态" @change="load">
        <el-option label="待执行" value="PENDING" />
        <el-option label="等待重试" value="RETRY_WAIT" />
        <el-option label="需人工处理" value="DEAD" />
        <el-option label="已完成" value="SUCCEEDED" />
      </el-select>
      <el-button @click="load">查询</el-button>
    </div>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert
      v-if="successText"
      type="success"
      :closable="false"
      :title="successText"
      class="alert"
    />
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

    <el-dialog v-model="abandonVisible" title="放弃任务" width="420px">
      <el-form label-width="92px">
        <el-form-item label="原因"
          ><el-input v-model="form.reason" type="textarea" :rows="2"
        /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="abandonVisible = false">取消</el-button>
        <el-button type="danger" @click="abandon">确认放弃</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.tasks-page {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 20px;
}
h1 {
  margin: 0 0 12px;
  font-size: 18px;
}
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.status {
  width: 160px;
}
.alert {
  margin-bottom: 12px;
}
</style>
