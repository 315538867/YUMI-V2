<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAuthStore } from '@/platform/stores/auth'
import { reportingService } from '@/modules/reporting/services/reportingService'
import CodeTag from '@/shared/components/CodeTag.vue'
import PageState from '@/shared/components/PageState.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'

const { loading, errorText: loadError, run: runLoad } = useAsyncLoad()

const auth = useAuthStore()
const successText = ref('')
const { loading: acting, run } = useAsyncAction()
const versions = ref<
  Array<{ versionBusinessId: string; status: string; recordCount: number; differenceCount: number }>
>([])
const canManage = auth.hasPermission('reporting.admin')

async function load(): Promise<void> {
  await runLoad(async () => {
    versions.value = await reportingService.versions()
  }, '版本列表加载失败')
}

async function ingest(): Promise<void> {
  const result = await run(() => reportingService.ingest(), { fallback: '来源归集失败' })
  if (result !== undefined) {
    successText.value = `归集完成：${result.recordCount} 条，失败 ${result.failureCount} 个来源`
    ElMessage.success(successText.value)
  }
}

async function rebuild(): Promise<void> {
  const result = await run(() => reportingService.rebuild(), { fallback: '影子重建失败' })
  if (result !== undefined) {
    successText.value = result.activated
      ? `影子版本 ${result.versionBusinessId} 已激活（${result.recordCount} 条）`
      : `重建失败保留旧投影：差异 ${result.differenceCount}`
    ElMessage.success(successText.value)
    await load()
  }
}

void load()
</script>

<template>
  <WorkspacePage
    class="rebuild-page"
    title="影子重建"
    hint="重建报表投影；差异未清零时保留旧可用版本"
  >
    <el-alert
      type="info"
      :closable="false"
      title="重建只影响报表投影，不修改业务事实；差异未清零时保留旧可用版本。"
      class="yumi-alert"
    />
    <el-alert
      v-if="successText"
      type="success"
      :closable="false"
      :title="successText"
      class="yumi-alert"
    />
    <el-alert
      v-if="!canManage"
      type="warning"
      :closable="false"
      title="当前账号没有报表管理权限，只能查看版本。"
      class="yumi-alert"
    />
    <div class="yumi-toolbar">
      <PermissionButton permission="reporting.admin" :loading="acting" @click="ingest"
        >归集来源</PermissionButton
      >
      <PermissionButton
        permission="reporting.admin"
        type="primary"
        :loading="acting"
        @click="rebuild"
        >发起影子重建</PermissionButton
      >
    </div>
    <PageState :loading="loading" :error="loadError" @retry="load">
      <el-table :data="versions" border empty-text="暂无投影版本">
        <el-table-column prop="versionBusinessId" label="版本" min-width="240" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }"
            ><CodeTag category="reporting.versionStatus" :code="row.status"
          /></template>
        </el-table-column>
        <el-table-column prop="recordCount" label="记录数" width="110" />
        <el-table-column prop="differenceCount" label="差异数" width="110" />
      </el-table>
    </PageState>
  </WorkspacePage>
</template>
