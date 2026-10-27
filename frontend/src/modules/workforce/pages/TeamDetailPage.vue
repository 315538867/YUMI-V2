<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { TeamView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import CodeTag from '@/shared/components/CodeTag.vue'
import PageState from '@/shared/components/PageState.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const route = useRoute()
const router = useRouter()
const teamId = String(route.params.teamBusinessId ?? '')
const team = ref<TeamView | null>(null)
const loading = ref(true)
const errorText = ref('')
const actionError = ref('')

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    team.value = await workforceService.team(teamId)
  } catch (error) {
    errorText.value = userMessageOf(error, '小组详情加载失败')
  } finally {
    loading.value = false
  }
}

async function deactivate(): Promise<void> {
  if (team.value === null) {
    return
  }
  actionError.value = ''
  try {
    await ElMessageBox.confirm(
      `停用小组「${team.value.name}」？历史排班与快照不受影响。`,
      '停用小组',
    )
    await workforceService.deactivateTeam(teamId, team.value.rowVersion)
    ElMessage.success('小组已停用')
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      actionError.value = userMessageOf(error, '小组停用失败')
    }
  }
}

load()
</script>

<template>
  <section class="team-detail">
    <PageState :loading="loading" :error="errorText" @retry="load">
      <template v-if="team">
        <header class="head">
          <div>
            <h1>{{ team.name }}</h1>
            <span class="meta">{{ team.teamNo }} · {{ team.members.length }} 名成员</span>
          </div>
          <div class="actions">
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
          </div>
        </header>

        <el-alert
          v-if="actionError"
          type="error"
          :closable="false"
          :title="actionError"
          class="alert"
        />

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
        <el-table :data="team.members" empty-text="小组暂无成员" border size="small">
          <el-table-column prop="employeeNo" label="员工编号" width="150" />
          <el-table-column prop="name" label="姓名" min-width="140" />
          <el-table-column label="在职状态" width="120">
            <template #default="{ row }">
              <CodeTag category="workforce.employmentStatus" :code="row.employmentStatus" />
            </template>
          </el-table-column>
        </el-table>
      </template>
    </PageState>
  </section>
</template>

<style scoped>
.team-detail {
  background: var(--yumi-surface);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 16px;
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
}

h1 {
  margin: 0 0 4px;
  font-size: 18px;
}

.meta {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.actions {
  display: flex;
  gap: 8px;
}

h2 {
  margin: 24px 0 10px;
  font-size: 16px;
}

.alert {
  margin-bottom: 12px;
}
</style>
