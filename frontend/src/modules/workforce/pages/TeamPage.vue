<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useListQuery } from '@/platform/composables/useListQuery'
import type { TeamStatus, TeamView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import CodeTag from '@/shared/components/CodeTag.vue'
import ListPager from '@/shared/components/ListPager.vue'
import PageState from '@/shared/components/PageState.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const router = useRouter()
const teams = ref<TeamView[]>([])
const loading = ref(false)
const errorText = ref('')
const { filters, page, size, sizes, search, reset, goPage, changeSize } = useListQuery(
  { keyword: '', status: '' as TeamStatus | '' },
  { onSearch: () => void load() },
)

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    teams.value = await workforceService.searchTeams(
      filters.keyword,
      filters.status,
      page.value,
      size.value,
    )
  } catch (error) {
    errorText.value = userMessageOf(error, '小组列表加载失败')
  } finally {
    loading.value = false
  }
}

void load()
</script>

<template>
  <section class="team-page">
    <div class="head">
      <h1>小组</h1>
      <PermissionButton
        permission="workforce.team.manage"
        type="primary"
        @click="router.push('/workforce/teams/new')"
      >
        新增小组
      </PermissionButton>
    </div>
    <div class="toolbar">
      <el-input
        v-model="filters.keyword"
        placeholder="小组名称或编号"
        clearable
        class="keyword"
        aria-label="小组名称或编号"
        @keyup.enter="search"
      />
      <el-select v-model="filters.status" placeholder="全部状态" clearable aria-label="小组状态">
        <el-option label="启用" value="ACTIVE" />
        <el-option label="停用" value="INACTIVE" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </div>
    <PageState :loading="loading" :error="errorText" @retry="load">
      <el-table :data="teams" empty-text="没有匹配的小组" border>
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
      <ListPager
        :page="page"
        :size="size"
        :count="teams.length"
        :sizes="sizes"
        @update:page="goPage"
        @update:size="changeSize"
      />
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

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.keyword {
  width: 220px;
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
