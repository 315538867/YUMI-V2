<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useAuthStore } from '@/platform/stores/auth'
import type { WorkforceStandardView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const auth = useAuthStore()
const standards = ref<WorkforceStandardView | null>(null)
const effectiveMinutes = ref(0)
const loading = ref(false)
const errorText = ref('')
const rowText = ref<Record<string, string>>({})
const canManage = auth.hasPermission('workforce.standard.manage')

async function load(): Promise<void> {
  loading.value = true
  try {
    standards.value = await workforceService.standards()
    effectiveMinutes.value = standards.value.effectiveMinutes
    rowText.value = Object.fromEntries(
      standards.value.efficiencyStandards.map((row) => [
        row.standardBusinessId,
        row.efficiencyFactor,
      ]),
    )
  } catch (error) {
    errorText.value = userMessageOf(error, '标准加载失败')
  } finally {
    loading.value = false
  }
}

async function saveWorkTime(): Promise<void> {
  if (standards.value === null) {
    return
  }
  try {
    standards.value = await workforceService.updateWorkTime({
      effectiveMinutes: Number(effectiveMinutes.value),
      expectedRowVersion: standards.value.dailyRowVersion,
    })
    ElMessage.success('每日有效时间已保存')
  } catch (error) {
    errorText.value = userMessageOf(error, '每日有效时间保存失败')
  }
}

async function saveEfficiency(standardBusinessId: string): Promise<void> {
  if (standards.value === null) {
    return
  }
  const row = standards.value.efficiencyStandards.find(
    (item) => item.standardBusinessId === standardBusinessId,
  )
  if (row === undefined) {
    return
  }
  try {
    standards.value = await workforceService.updateEfficiency({
      standardBusinessId,
      memberCount: row.memberCount,
      efficiencyFactor: rowText.value[standardBusinessId] ?? row.efficiencyFactor,
      expectedRowVersion: row.rowVersion,
    })
    ElMessage.success('效率标准已保存')
  } catch (error) {
    errorText.value = userMessageOf(error, '效率标准保存失败')
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="standards-page">
    <h1>人员标准</h1>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <template v-if="standards">
      <h2>每日有效时间</h2>
      <el-form inline>
        <el-form-item label="有效分钟">
          <el-input
            v-model.number="effectiveMinutes"
            type="number"
            class="short"
            :disabled="!canManage"
          />
        </el-form-item>
        <PermissionButton
          permission="workforce.standard.manage"
          type="primary"
          @click="saveWorkTime"
          >保存</PermissionButton
        >
      </el-form>

      <h2>小组人数效率标准</h2>
      <el-table :data="standards.efficiencyStandards" border size="small">
        <el-table-column prop="memberCount" label="人数" width="100" />
        <el-table-column label="效率因子" width="180">
          <template #default="{ row }">
            <el-input v-model="rowText[row.standardBusinessId]" :disabled="!canManage" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100">
          <template #default="{ row }">
            <PermissionButton
              permission="workforce.standard.manage"
              link
              type="primary"
              @click="saveEfficiency(row.standardBusinessId)"
            >
              保存
            </PermissionButton>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </section>
</template>

<style scoped>
.standards-page {
  background: var(--yumi-surface);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 20px;
}

h1 {
  margin: 0 0 8px;
  font-size: 18px;
}

h2 {
  margin: 20px 0 10px;
  font-size: 16px;
}

.short {
  width: 140px;
}

.alert {
  margin-bottom: 12px;
}
</style>
