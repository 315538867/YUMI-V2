<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import { useAuthStore } from '@/platform/stores/auth'
import type { TaskView } from '@/modules/production/contracts'
import { productionService } from '@/modules/production/services/productionService'
import CodeTag from '@/shared/components/CodeTag.vue'
import DetailPage from '@/shared/components/DetailPage.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const task = ref<TaskView | null>(null)
const { loading, errorText, run } = useAsyncLoad()
const canSchedule = auth.hasPermission('production.schedule')

async function load(): Promise<void> {
  await run(async () => {
    task.value = await productionService.task(String(route.params.taskBusinessId ?? ''))
  }, '任务详情加载失败')
}

onMounted(load)
</script>

<template>
  <DetailPage
    class="task-detail"
    :title="task?.taskNo ?? '任务详情'"
    :loading="loading"
    :error="errorText"
    @retry="load"
  >
    <template #meta>
      <template v-if="task">
        <CodeTag category="production.taskType" :code="task.taskType" /> ·
        <CodeTag category="production.taskStatus" :code="task.status" /> ·
        {{ task.workDate }}
      </template>
    </template>
    <template #actions>
      <template v-if="task">
        <el-button
          v-if="canSchedule && (task.status === 'ARRANGED' || task.status === 'PARTIALLY_VERIFIED')"
          @click="router.push(`/production/tasks/${task.taskBusinessId}/adjust`)"
        >
          调整
        </el-button>
        <el-button @click="router.push(`/production/verify?task=${task.taskBusinessId}`)"
          >核验</el-button
        >
      </template>
    </template>

    <template v-if="task">
      <el-descriptions :column="3" border>
        <el-descriptions-item label="执行主体">
          <CodeTag category="production.subjectType" :code="task.subject.subjectType" /> /
          {{ task.subject.subjectBusinessId }}
        </el-descriptions-item>
        <el-descriptions-item label="工序">
          <CodeTag
            v-if="task.processCode"
            category="production.targetProcess"
            :code="task.processCode"
          />
          <span v-else>其他任务</span>
        </el-descriptions-item>
        <el-descriptions-item label="计划量">{{ task.plannedWorkQuantity }}</el-descriptions-item>
        <el-descriptions-item label="标准秒数">{{ task.taskStandardSeconds }}</el-descriptions-item>
        <el-descriptions-item v-if="task.otherTask" label="工作内容" :span="2">
          {{ task.otherTask.workContent }}
        </el-descriptions-item>
      </el-descriptions>

      <h2>任务明细</h2>
      <el-table :data="task.items" empty-text="其他任务没有生产明细" border>
        <el-table-column prop="taskItemBusinessId" label="明细标识" min-width="200" />
        <el-table-column prop="productionSourceBusinessId" label="生产来源" min-width="200" />
        <el-table-column prop="plannedQuantity" label="计划量" width="90" />
        <el-table-column prop="itemStandardMinutes" label="单件分钟" width="100" />
        <el-table-column label="核验状态" width="120">
          <template #default="{ row }">
            <CodeTag category="production.verificationStatus" :code="row.verificationStatus" />
          </template>
        </el-table-column>
      </el-table>
    </template>
  </DetailPage>
</template>
