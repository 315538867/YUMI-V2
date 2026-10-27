<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type {
  TeamHistoryView,
  TeamView,
  WorkforceActivityReferenceView,
} from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import CodeLabel from '@/shared/components/CodeLabel.vue'
import CodeTag from '@/shared/components/CodeTag.vue'
import DateTimeText from '@/shared/components/DateTimeText.vue'
import DetailPage from '@/shared/components/DetailPage.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const route = useRoute()
const router = useRouter()
const teamId = String(route.params.teamBusinessId ?? '')
const team = ref<TeamView | null>(null)
const history = ref<TeamHistoryView | null>(null)
const references = ref<WorkforceActivityReferenceView[]>([])
const { loading, errorText, run } = useAsyncLoad()
const { run: runAction } = useAsyncAction()

async function load(): Promise<void> {
  await run(async () => {
    const [view, teamHistory, activeReferences] = await Promise.all([
      workforceService.team(teamId),
      workforceService.teamHistory(teamId),
      workforceService.subjectReferences('TEAM', teamId),
    ])
    team.value = view
    history.value = teamHistory
    references.value = activeReferences
  }, '小组详情加载失败')
}

async function deactivate(): Promise<void> {
  if (team.value === null) {
    return
  }
  let reason = ''
  try {
    const result = await ElMessageBox.prompt(
      `停用小组「${team.value.name}」？历史排班与快照不受影响。`,
      '停用小组',
      {
        inputPlaceholder: '请填写停用原因',
        inputValidator: (value) => (value.trim() === '' ? '停用原因不能为空' : true),
      },
    )
    reason = result.value
  } catch {
    return
  }
  await runAction(() => workforceService.deactivateTeam(teamId, team.value!.rowVersion, reason), {
    success: '小组已停用',
    fallback: '小组停用失败',
    onSuccess: load,
  })
}

void load()
</script>

<template>
  <DetailPage
    class="team-detail"
    :title="team?.name ?? '小组详情'"
    :loading="loading"
    :error="errorText"
    @retry="load"
  >
    <template #meta>
      <template v-if="team">{{ team.teamNo }} · {{ team.members.length }} 名成员</template>
    </template>
    <template #actions>
      <template v-if="team">
        <PermissionButton
          permission="workforce.team.manage"
          @click="router.push(`/workforce/teams/${teamId}/edit`)"
        >
          编辑小组
        </PermissionButton>
        <PermissionButton
          v-if="team.status === 'ACTIVE'"
          permission="workforce.team.manage"
          type="warning"
          @click="deactivate"
        >
          停用小组
        </PermissionButton>
      </template>
    </template>

    <template v-if="team">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="状态">
          <CodeTag category="workforce.teamStatus" :code="team.status" />
        </el-descriptions-item>
        <el-descriptions-item label="效率因子">{{ team.efficiencyFactor }}</el-descriptions-item>
        <el-descriptions-item label="内容版本">{{ team.contentVersion }}</el-descriptions-item>
        <el-descriptions-item label="标准内容版本">
          {{ team.efficiencyStandardContentVersion }}
        </el-descriptions-item>
      </el-descriptions>

      <h2>小组成员</h2>
      <el-table
        class="member-table"
        :data="team.members"
        empty-text="小组暂无成员"
        border
        size="small"
      >
        <el-table-column prop="employeeNo" label="员工编号" width="150" />
        <el-table-column prop="name" label="姓名" min-width="140" />
        <el-table-column label="在职状态" width="120">
          <template #default="{ row }">
            <CodeTag category="workforce.employmentStatus" :code="row.employmentStatus" />
          </template>
        </el-table-column>
      </el-table>

      <el-tabs class="history-tabs">
        <el-tab-pane label="成员变更">
          <el-table
            :data="history?.members ?? []"
            empty-text="暂无成员变更记录"
            border
            size="small"
          >
            <el-table-column label="员工" min-width="180">
              <template #default="{ row }">
                {{ row.employeeName || row.employeeBusinessId }}
                <span v-if="row.employeeNo" class="muted">（{{ row.employeeNo }}）</span>
              </template>
            </el-table-column>
            <el-table-column label="变更" width="100">
              <template #default="{ row }">
                {{
                  row.factType === 'JOIN'
                    ? '加入'
                    : row.factType === 'LEAVE'
                      ? '离开'
                      : row.factType
                }}
              </template>
            </el-table-column>
            <el-table-column prop="operatorId" label="操作人" width="140" />
            <el-table-column label="记录时间" width="200">
              <template #default="{ row }"><DateTimeText :value="row.occurredAt" /></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="状态记录">
          <el-table :data="history?.statuses ?? []" empty-text="暂无状态记录" border size="small">
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                {{ row.factType === 'CREATE' ? '创建' : '停用' }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <CodeTag category="workforce.teamStatus" :code="row.status" />
              </template>
            </el-table-column>
            <el-table-column label="说明" min-width="200">
              <template #default="{ row }">{{ row.reason || '—' }}</template>
            </el-table-column>
            <el-table-column prop="operatorId" label="操作人" width="140" />
            <el-table-column label="记录时间" width="200">
              <template #default="{ row }"><DateTimeText :value="row.occurredAt" /></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane :label="`活动引用（${references.length}）`">
          <el-table
            :data="references"
            empty-text="当前没有活动任务引用，可以变更成员或停用小组"
            border
            size="small"
          >
            <el-table-column label="来源模块" width="130">
              <template #default="{ row }">
                <CodeLabel
                  category="workforce.activitySourceModule"
                  :code="row.sourceModule"
                  fallback="其他模块"
                />
              </template>
            </el-table-column>
            <el-table-column label="来源类型" width="150">
              <template #default="{ row }">
                <CodeLabel
                  category="workforce.activitySourceType"
                  :code="row.sourceType"
                  fallback="其他来源"
                />
              </template>
            </el-table-column>
            <el-table-column prop="sourceBusinessId" label="来源标识" min-width="200" />
            <el-table-column label="工作日期" width="120">
              <template #default="{ row }">{{ row.workDate || '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <CodeTag category="workforce.reservationStatus" :code="row.status" />
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </template>
  </DetailPage>
</template>

<style scoped>
.history-tabs {
  margin-top: 20px;
}

.muted {
  color: var(--el-text-color-secondary);
}
</style>
