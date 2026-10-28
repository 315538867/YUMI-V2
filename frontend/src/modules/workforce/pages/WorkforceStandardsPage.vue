<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useAuthStore } from '@/platform/stores/auth'
import type { StandardVersionView, WorkforceStandardView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import DateTimeText from '@/shared/components/DateTimeText.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'
import PageShell from '@/shared/components/PageShell.vue'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'

const { loading, errorText, run } = useAsyncLoad()
const { loading: versionLoading, errorText: versionError, run: runVersion } = useAsyncLoad()
const { run: runAction } = useAsyncAction()

const auth = useAuthStore()
const standards = ref<WorkforceStandardView | null>(null)
const effectiveMinutes = ref(0)
const rowText = ref<Record<string, string>>({})
const canManage = auth.hasPermission('workforce.standard.manage')
const reasonText = ref('')
const versionVisible = ref(false)
const versionTitle = ref('')
const versions = ref<StandardVersionView[]>([])
const createVisible = ref(false)
const createForm = reactive({ memberCount: 5, efficiencyFactor: '' })

async function load(): Promise<void> {
  await run(async () => {
    standards.value = await workforceService.standards()
    effectiveMinutes.value = standards.value.effectiveMinutes
    rowText.value = Object.fromEntries(
      standards.value.efficiencyStandards.map((row) => [
        row.standardBusinessId,
        row.efficiencyFactor,
      ]),
    )
  }, '标准加载失败')
}

async function saveWorkTime(): Promise<void> {
  if (standards.value === null) {
    return
  }
  await runAction(
    () =>
      workforceService.updateWorkTime({
        effectiveMinutes: Number(effectiveMinutes.value),
        expectedRowVersion: standards.value!.dailyRowVersion,
        reason: reasonText.value.trim(),
      }),
    {
      success: '每日有效时间已保存',
      fallback: '每日有效时间保存失败',
      onSuccess: (saved) => {
        standards.value = saved
      },
    },
  )
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
  await runAction(
    () =>
      workforceService.updateEfficiency({
        standardBusinessId,
        memberCount: row.memberCount,
        efficiencyFactor: rowText.value[standardBusinessId] ?? row.efficiencyFactor,
        expectedRowVersion: row.rowVersion,
        reason: reasonText.value.trim(),
      }),
    {
      success: '效率标准已保存',
      fallback: '效率标准保存失败',
      onSuccess: (saved) => {
        standards.value = saved
      },
    },
  )
}

function openCreate(): void {
  createForm.memberCount = 5
  createForm.efficiencyFactor = ''
  createVisible.value = true
}

async function createStandard(): Promise<void> {
  await runAction(
    () =>
      workforceService.createEfficiency({
        memberCount: Number(createForm.memberCount),
        efficiencyFactor: createForm.efficiencyFactor,
        reason: reasonText.value.trim(),
      }),
    {
      success: '人数效率标准已创建',
      fallback: '人数效率标准创建失败',
      onSuccess: (saved) => {
        standards.value = saved
        createVisible.value = false
        reasonText.value = ''
      },
    },
  )
}

async function openVersions(
  type: 'EFFICIENCY' | 'DAILY_WORK_TIME',
  standardBusinessId: string,
  title: string,
): Promise<void> {
  versionVisible.value = true
  versionTitle.value = title
  versions.value = []
  await runVersion(async () => {
    const result = await workforceService.standardHistory(type, standardBusinessId)
    versions.value = result.versions
  }, '版本记录加载失败')
}

function versionValue(version: StandardVersionView): string {
  if (version.memberCount !== null && version.efficiencyFactor !== null) {
    return `${version.memberCount} 人 · ${version.efficiencyFactor} 倍`
  }
  return version.effectiveMinutes === null ? '—' : `${version.effectiveMinutes} 分钟`
}

onMounted(load)
</script>

<template>
  <PageShell title="人员标准">
    <WorkspacePage v-loading="loading" class="standards-page">
      <el-alert
        v-if="errorText"
        type="error"
        :closable="false"
        :title="errorText"
        class="yumi-alert"
      />
      <template v-if="standards">
        <el-form inline class="reason-form">
          <el-form-item label="调整说明">
            <el-input
              v-model="reasonText"
              placeholder="可选，写入版本记录"
              class="reason"
              :disabled="!canManage"
            />
          </el-form-item>
        </el-form>

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
          <el-form-item label="当前版本">V{{ standards.dailyContentVersion }}</el-form-item>
          <el-form-item label="活动引用">{{ standards.dailyActiveReferenceCount }}</el-form-item>
          <PermissionButton
            permission="workforce.standard.manage"
            type="primary"
            @click="saveWorkTime"
            >保存</PermissionButton
          >
          <el-button
            class="history-button"
            @click="
              openVersions('DAILY_WORK_TIME', standards.dailyStandardBusinessId, '每日有效时间')
            "
          >
            版本记录
          </el-button>
        </el-form>

        <div class="yumi-section-head">
          <h2>小组人数效率标准</h2>
          <PermissionButton
            permission="workforce.standard.manage"
            type="primary"
            @click="openCreate"
          >
            新增人数标准
          </PermissionButton>
        </div>
        <el-table
          class="efficiency-table"
          :data="standards.efficiencyStandards"
          border
          size="small"
        >
          <el-table-column prop="memberCount" label="人数" width="100" />
          <el-table-column label="效率因子" width="180">
            <template #default="{ row }">
              <el-input v-model="rowText[row.standardBusinessId]" :disabled="!canManage" />
            </template>
          </el-table-column>
          <el-table-column label="版本" width="80">
            <template #default="{ row }">V{{ row.contentVersion }}</template>
          </el-table-column>
          <el-table-column label="活动引用" width="100">
            <template #default="{ row }">{{ row.activeReferenceCount }}</template>
          </el-table-column>
          <el-table-column label="操作" width="190">
            <template #default="{ row }">
              <PermissionButton
                permission="workforce.standard.manage"
                link
                type="primary"
                @click="saveEfficiency(row.standardBusinessId)"
              >
                保存
              </PermissionButton>
              <el-button
                link
                type="primary"
                @click="
                  openVersions(
                    'EFFICIENCY',
                    row.standardBusinessId,
                    `${row.memberCount} 人效率标准`,
                  )
                "
              >
                版本记录
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <el-dialog v-model="createVisible" title="新增人数效率标准" width="440px">
        <el-form label-width="100px">
          <el-form-item label="人数">
            <el-input v-model.number="createForm.memberCount" type="number" />
          </el-form-item>
          <el-form-item label="效率因子">
            <el-input v-model="createForm.efficiencyFactor" placeholder="必须大于 1" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="createVisible = false">取消</el-button>
          <el-button type="primary" @click="createStandard">创建</el-button>
        </template>
      </el-dialog>

      <el-dialog v-model="versionVisible" :title="`${versionTitle} · 版本记录`" width="640px">
        <el-alert
          v-if="versionError"
          type="error"
          :closable="false"
          :title="versionError"
          class="yumi-alert"
        />
        <el-table
          v-loading="versionLoading"
          :data="versions"
          empty-text="暂无版本记录"
          border
          size="small"
        >
          <el-table-column label="版本" width="80">
            <template #default="{ row }">V{{ row.contentVersion }}</template>
          </el-table-column>
          <el-table-column label="值" width="140">
            <template #default="{ row }">{{ versionValue(row) }}</template>
          </el-table-column>
          <el-table-column label="说明" min-width="160">
            <template #default="{ row }">{{ row.reason || '—' }}</template>
          </el-table-column>
          <el-table-column prop="operatorId" label="操作人" width="140" />
          <el-table-column label="时间" width="200">
            <template #default="{ row }"><DateTimeText :value="row.occurredAt" /></template>
          </el-table-column>
        </el-table>
      </el-dialog>
    </WorkspacePage>
  </PageShell>
</template>

<style scoped>
.short {
  width: 140px;
}

.reason-form {
  margin-bottom: var(--yumi-space-1);
}

.reason {
  width: 280px;
}

.history-button {
  margin-left: var(--yumi-space-2);
}
</style>
