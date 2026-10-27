<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { userMessageOf } from '@/platform/services/errorMessage'
import { reportingService } from '@/modules/reporting/services/reportingService'
import CodeTag from '@/shared/components/CodeTag.vue'

interface VersionRow {
  versionBusinessId: string
  status: string
  recordCount: number
  differenceCount: number
  builtAt: string | null
  activatedAt: string | null
}

const versions = ref<VersionRow[]>([])
const selected = ref('')
const differences = ref<Array<{ differenceBusinessId: string; feedCode: string; detailText: string }>>([])
const errorText = ref('')

async function load(): Promise<void> {
  try {
    versions.value = await reportingService.versions()
    if (selected.value === '' && versions.value.length > 0) {
      selected.value = versions.value[0]!.versionBusinessId
    }
    await loadDifferences()
  } catch (error) {
    errorText.value = userMessageOf(error, '投影版本加载失败')
  }
}

async function loadDifferences(): Promise<void> {
  if (selected.value === '') {
    differences.value = []
    return
  }
  differences.value = await reportingService.differences(selected.value)
}

onMounted(load)
</script>

<template>
  <section class="reconciliation-page">
    <h1>来源对账</h1>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-table :data="versions" border>
      <el-table-column prop="versionBusinessId" label="投影版本" min-width="220" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }"><CodeTag category="reporting.versionStatus" :code="row.status" /></template>
      </el-table-column>
      <el-table-column prop="recordCount" label="记录数" width="100" />
      <el-table-column prop="differenceCount" label="差异数" width="100" />
      <el-table-column prop="activatedAt" label="切换时间" width="230" />
      <el-table-column label="操作" width="110">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="selected = row.versionBusinessId; loadDifferences()"
          >
            查看差异
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <h2>差异 {{ selected }}</h2>
    <el-table :data="differences" border empty-text="该版本无来源差异">
      <el-table-column label="来源" width="160">
        <template #default="{ row }"><CodeTag category="reporting.feedCode" :code="row.feedCode" /></template>
      </el-table-column>
      <el-table-column prop="differenceBusinessId" label="差异标识" min-width="220" />
      <el-table-column prop="detailText" label="说明" min-width="320" />
    </el-table>
  </section>
</template>

<style scoped>
.reconciliation-page { background: #fff; border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 20px; }
h1 { margin: 0 0 12px; font-size: 18px; }
h2 { margin: 20px 0 10px; font-size: 15px; }
.alert { margin-bottom: 12px; }
</style>
