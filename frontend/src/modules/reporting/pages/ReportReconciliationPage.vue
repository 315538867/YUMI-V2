<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useListQuery } from '@/platform/composables/useListQuery'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import { reportingService } from '@/modules/reporting/services/reportingService'
import CodeTag from '@/shared/components/CodeTag.vue'
import DateTimeText from '@/shared/components/DateTimeText.vue'
import PageState from '@/shared/components/PageState.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'
import PageShell from '@/shared/components/PageShell.vue'

const { loading, errorText, run } = useAsyncLoad(true)

interface VersionRow {
  versionBusinessId: string
  status: string
  recordCount: number
  differenceCount: number
  builtAt: string | null
  activatedAt: string | null
}

const versions = ref<VersionRow[]>([])
const differences = ref<
  Array<{ differenceBusinessId: string; feedCode: string; detailText: string }>
>([])
const { filters, search, sync } = useListQuery(
  { version: '' },
  { paged: false, onSearch: () => void loadDifferences() },
)
const selected = computed(() => filters.version)

async function load(): Promise<void> {
  await run(async () => {
    versions.value = await reportingService.versions()
    if (!versions.value.some((item) => item.versionBusinessId === filters.version)) {
      filters.version = versions.value[0]?.versionBusinessId ?? ''
    }
    await sync()
    await loadDifferences()
  }, '投影版本加载失败')
}

async function selectVersion(versionBusinessId: string): Promise<void> {
  filters.version = versionBusinessId
  await search()
}

async function loadDifferences(): Promise<void> {
  if (filters.version === '') {
    differences.value = []
    return
  }
  differences.value = await reportingService.differences(filters.version)
}

onMounted(load)
</script>

<template>
  <PageShell title="来源对账">
    <WorkspacePage class="reconciliation-page">
      <PageState
        :loading="loading"
        :error="errorText"
        :empty="!loading && errorText === '' && versions.length === 0"
        empty-text="暂无投影版本"
        @retry="load"
      >
        <el-table :data="versions" border>
          <el-table-column prop="versionBusinessId" label="投影版本" min-width="220" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }"
              ><CodeTag category="reporting.versionStatus" :code="row.status"
            /></template>
          </el-table-column>
          <el-table-column prop="recordCount" label="记录数" width="100" />
          <el-table-column prop="differenceCount" label="差异数" width="100" />
          <el-table-column label="切换时间" width="230">
            <template #default="{ row }"><DateTimeText :value="row.activatedAt" /></template>
          </el-table-column>
          <el-table-column label="操作" width="110">
            <template #default="{ row }">
              <el-button link type="primary" @click="selectVersion(row.versionBusinessId)">
                查看差异
              </el-button>
            </template>
          </el-table-column>
        </el-table>

        <h2>差异 {{ selected }}</h2>
        <el-table :data="differences" border empty-text="该版本无来源差异">
          <el-table-column label="来源" width="160">
            <template #default="{ row }"
              ><CodeTag category="reporting.feedCode" :code="row.feedCode"
            /></template>
          </el-table-column>
          <el-table-column prop="differenceBusinessId" label="差异标识" min-width="220" />
          <el-table-column prop="detailText" label="说明" min-width="320" />
        </el-table>
      </PageState>
    </WorkspacePage>
  </PageShell>
</template>
