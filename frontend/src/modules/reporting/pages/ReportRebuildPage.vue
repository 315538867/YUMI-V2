<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useAuthStore } from '@/platform/stores/auth'
import { reportingService } from '@/modules/reporting/services/reportingService'
import CodeTag from '@/shared/components/CodeTag.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const auth = useAuthStore()
const errorText = ref('')
const successText = ref('')
const rebuilding = ref(false)
const versions = ref<
  Array<{ versionBusinessId: string; status: string; recordCount: number; differenceCount: number }>
>([])
const canManage = auth.hasPermission('reporting.admin')

async function load(): Promise<void> {
  try {
    versions.value = await reportingService.versions()
  } catch (error) {
    errorText.value = userMessageOf(error, '版本列表加载失败')
  }
}

async function ingest(): Promise<void> {
  try {
    const result = await reportingService.ingest()
    successText.value = `归集完成：${result.recordCount} 条，失败 ${result.failureCount} 个来源`
    ElMessage.success(successText.value)
  } catch (error) {
    errorText.value = userMessageOf(error, '来源归集失败')
  }
}

async function rebuild(): Promise<void> {
  rebuilding.value = true
  errorText.value = ''
  try {
    const result = await reportingService.rebuild()
    successText.value = result.activated
      ? `影子版本 ${result.versionBusinessId} 已激活（${result.recordCount} 条）`
      : `重建失败保留旧投影：差异 ${result.differenceCount}`
    ElMessage.success(successText.value)
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '影子重建失败')
  } finally {
    rebuilding.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="rebuild-page">
    <h1>影子重建</h1>
    <el-alert
      type="info"
      :closable="false"
      title="重建只影响报表投影，不修改业务事实；差异未清零时保留旧可用版本。"
      class="alert"
    />
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-alert
      v-if="successText"
      type="success"
      :closable="false"
      :title="successText"
      class="alert"
    />
    <el-alert
      v-if="!canManage"
      type="warning"
      :closable="false"
      title="当前账号没有报表管理权限，只能查看版本。"
      class="alert"
    />
    <div class="toolbar">
      <PermissionButton permission="reporting.admin" @click="ingest">归集来源</PermissionButton>
      <PermissionButton
        permission="reporting.admin"
        type="primary"
        :loading="rebuilding"
        @click="rebuild"
        >发起影子重建</PermissionButton
      >
    </div>
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
  </section>
</template>

<style scoped>
.rebuild-page {
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
.alert {
  margin-bottom: 12px;
}
</style>
