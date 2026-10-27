<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type {
  EmployeeHistoryView,
  EmployeeView,
  WorkforceActivityReferenceView,
} from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import CodeLabel from '@/shared/components/CodeLabel.vue'
import CodeTag from '@/shared/components/CodeTag.vue'
import DateTimeText from '@/shared/components/DateTimeText.vue'
import DetailPage from '@/shared/components/DetailPage.vue'
import FormErrorAlert from '@/shared/components/FormErrorAlert.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

type DialogKind = '' | 'qualification' | 'leave' | 'rehire'

const route = useRoute()
const router = useRouter()
const employeeId = String(route.params.employeeBusinessId ?? '')
const employee = ref<EmployeeView | null>(null)
const history = ref<EmployeeHistoryView | null>(null)
const references = ref<WorkforceActivityReferenceView[]>([])
const { loading, errorText, run } = useAsyncLoad()
const { loading: saving, run: runAction } = useAsyncAction()
const dialog = ref<DialogKind>('')
const dialogVisible = ref(false)
const formError = ref('')

const today = new Date().toISOString().slice(0, 10)
const form = reactive({
  workTypeCode: 'MAKING',
  qualificationAction: 'grant' as 'grant' | 'revoke',
  effectiveDate: today,
  reason: '',
})

function employmentTypeText(type: string): string {
  return { HIRE: '入职', LEAVE: '离职', REHIRE: '返聘' }[type] ?? type
}

function qualificationTypeText(type: string): string {
  return type === 'GRANT' ? '授予资格' : type === 'REVOKE' ? '撤销资格' : type
}

const dialogTitle = computed(() => {
  if (dialog.value === 'qualification') {
    return '工种资格'
  }
  if (dialog.value === 'leave') {
    return '登记离职'
  }
  return dialog.value === 'rehire' ? '登记返聘' : ''
})

async function load(): Promise<void> {
  await run(async () => {
    const [view, employeeHistory, activeReferences] = await Promise.all([
      workforceService.employee(employeeId),
      workforceService.employeeHistory(employeeId),
      workforceService.subjectReferences('EMPLOYEE', employeeId),
    ])
    employee.value = view
    history.value = employeeHistory
    references.value = activeReferences
  }, '员工详情加载失败')
}

function openDialog(kind: Exclude<DialogKind, ''>): void {
  dialog.value = kind
  formError.value = ''
  form.workTypeCode = 'MAKING'
  form.qualificationAction = 'grant'
  form.effectiveDate = today
  form.reason = ''
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (employee.value === null) {
    return
  }
  formError.value = ''
  if (dialog.value === 'leave' && form.reason.trim() === '') {
    formError.value = '请填写离职原因'
    return
  }
  const kind = dialog.value
  let success = '返聘已登记'
  if (kind === 'qualification') {
    success = form.qualificationAction === 'grant' ? '资格已授予' : '资格已撤销'
  } else if (kind === 'leave') {
    success = '离职已登记'
  }
  await runAction(
    async () => {
      if (kind === 'qualification') {
        await workforceService.changeQualification(employeeId, form.qualificationAction, {
          workTypeCode: form.workTypeCode,
          effectiveDate: form.effectiveDate,
        })
      } else if (kind === 'leave') {
        await workforceService.leave(employeeId, {
          effectiveDate: form.effectiveDate,
          reason: form.reason,
          expectedRowVersion: employee.value!.rowVersion,
        })
      } else if (kind === 'rehire') {
        await workforceService.rehire(employeeId, {
          effectiveDate: form.effectiveDate,
          expectedRowVersion: employee.value!.rowVersion,
        })
      }
    },
    {
      success,
      fallback: '操作失败',
      onSuccess: async () => {
        dialogVisible.value = false
        await load()
      },
    },
  )
}

void load()
</script>

<template>
  <DetailPage
    class="employee-detail"
    :title="employee?.name ?? '员工详情'"
    :loading="loading"
    :error="errorText"
    @retry="load"
  >
    <template #meta>
      <template v-if="employee">
        {{ employee.employeeNo }} · 更新于 <DateTimeText :value="employee.updatedAt" />
      </template>
    </template>
    <template #actions>
      <template v-if="employee">
        <PermissionButton
          permission="workforce.employee.manage"
          @click="router.push(`/workforce/employees/${employeeId}/edit`)"
        >
          编辑资料
        </PermissionButton>
        <PermissionButton
          permission="workforce.employee.manage"
          @click="openDialog('qualification')"
        >
          工种资格
        </PermissionButton>
        <PermissionButton
          v-if="employee.employmentStatus === 'EMPLOYED'"
          permission="workforce.employee.manage"
          type="warning"
          @click="openDialog('leave')"
        >
          登记离职
        </PermissionButton>
        <PermissionButton
          v-else
          permission="workforce.employee.manage"
          type="success"
          @click="openDialog('rehire')"
        >
          登记返聘
        </PermissionButton>
      </template>
    </template>

    <template v-if="employee">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="电话">{{ employee.phone }}</el-descriptions-item>
        <el-descriptions-item label="在职状态">
          <CodeTag category="workforce.employmentStatus" :code="employee.employmentStatus" />
        </el-descriptions-item>
        <el-descriptions-item label="最近生效日">
          {{ employee.latestEffectiveDate }}
        </el-descriptions-item>
        <el-descriptions-item label="内容版本">{{ employee.contentVersion }}</el-descriptions-item>
      </el-descriptions>

      <h2>当前工种资格</h2>
      <el-table
        class="qualification-table"
        :data="employee.qualifications"
        empty-text="尚未授予工种资格"
        border
        size="small"
      >
        <el-table-column label="工种" width="160">
          <template #default="{ row }">
            <CodeTag category="workforce.workType" :code="row.workTypeCode" />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <CodeTag category="workforce.qualificationStatus" :code="row.status" />
          </template>
        </el-table-column>
        <el-table-column prop="effectiveFrom" label="生效日期" width="140" />
      </el-table>

      <el-tabs class="history-tabs">
        <el-tab-pane label="就业记录">
          <el-table :data="history?.employment ?? []" empty-text="暂无就业记录" border size="small">
            <el-table-column prop="effectiveDate" label="生效日期" width="140" />
            <el-table-column label="变更" width="110">
              <template #default="{ row }">{{ employmentTypeText(row.factType) }}</template>
            </el-table-column>
            <el-table-column label="说明" min-width="220">
              <template #default="{ row }">{{ row.reason || '—' }}</template>
            </el-table-column>
            <el-table-column prop="operatorId" label="操作人" width="140" />
            <el-table-column label="记录时间" width="200">
              <template #default="{ row }"><DateTimeText :value="row.occurredAt" /></template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="资格变更">
          <el-table
            :data="history?.qualifications ?? []"
            empty-text="暂无资格变更记录"
            border
            size="small"
          >
            <el-table-column prop="effectiveDate" label="生效日期" width="140" />
            <el-table-column label="工种" width="150">
              <template #default="{ row }">
                <CodeTag category="workforce.workType" :code="row.workTypeCode" />
              </template>
            </el-table-column>
            <el-table-column label="变更" width="110">
              <template #default="{ row }">{{ qualificationTypeText(row.factType) }}</template>
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
            empty-text="当前没有活动引用，可办理离职或调整资格"
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
            <el-table-column label="工种" width="130">
              <template #default="{ row }">
                <CodeTag
                  v-if="row.workTypeCode"
                  category="workforce.workType"
                  :code="row.workTypeCode"
                />
                <span v-else>—</span>
              </template>
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

  <el-dialog v-model="dialogVisible" :title="dialogTitle" width="460px">
    <FormErrorAlert :error="formError" class="alert" />
    <el-form label-width="92px">
      <template v-if="dialog === 'qualification'">
        <el-form-item label="工种">
          <el-select v-model="form.workTypeCode" aria-label="工种">
            <el-option label="制作" value="MAKING" />
            <el-option label="捏毛装袋" value="BAGGING" />
            <el-option label="缝边剪袋" value="SEAM_CUTTING" />
          </el-select>
        </el-form-item>
        <el-form-item label="动作">
          <el-radio-group v-model="form.qualificationAction">
            <el-radio value="grant">授予</el-radio>
            <el-radio value="revoke">撤销</el-radio>
          </el-radio-group>
        </el-form-item>
      </template>
      <el-form-item v-if="dialog === 'leave'" label="离职原因">
        <el-input v-model="form.reason" type="textarea" :rows="2" aria-label="离职原因" />
      </el-form-item>
      <el-form-item label="生效日期">
        <el-date-picker
          v-model="form.effectiveDate"
          type="date"
          value-format="YYYY-MM-DD"
          aria-label="生效日期"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">确认</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.history-tabs {
  margin-top: 20px;
}

.alert {
  margin-bottom: 12px;
}
</style>
