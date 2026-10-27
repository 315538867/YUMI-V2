<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { TeamView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import CodeTag from '@/shared/components/CodeTag.vue'
import PageState from '@/shared/components/PageState.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const router = useRouter()
const teams = ref<TeamView[]>([])
const loading = ref(false)
const errorText = ref('')

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    teams.value = await workforceService.teams()
  } catch (error) {
    errorText.value = userMessageOf(error, '小组列表加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="team-page">
    <div class="toolbar">
      <h1>小组</h1>
      <PermissionButton
        permission="workforce.team.manage"
        type="primary"
        @click="router.push('/workforce/teams/new')"
      >
        新增小组
      </PermissionButton>
    </div>
    <PageState :loading="loading" :error="errorText" @retry="load">
      <el-table :data="teams" empty-text="暂无小组" border>
        <el-table-column prop="teamNo" label="小组编号" width="130" />
        <el-table-column prop="name" label="名称" width="160" />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <CodeTag category="workforce.teamStatus" :code="row.status" />
          </template>
        </el-table-column>
        <el-table-column label="成员" min-width="240">
          <template #default="{ row }">
            <el-tag v-for="member in row.members" :key="member.employeeBusinessId" class="tag">
              {{ member.name }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="efficiencyFactor" label="效率因子" width="110" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="router.push(`/workforce/teams/${row.teamBusinessId}`)"
            >
              查看
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageState>
  </section>
</template>

<style scoped>
.team-page {
  background: var(--yumi-surface);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

h1 {
  margin: 0;
  font-size: 18px;
}

.tag {
  margin-right: 4px;
}

.members {
  width: 100%;
}

.alert {
  margin-bottom: 12px;
}
</style>
