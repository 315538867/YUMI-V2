<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { EmployeeView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import CodeTag from '@/shared/components/CodeTag.vue'
import DateTimeText from '@/shared/components/DateTimeText.vue'
import PageState from '@/shared/components/PageState.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

type DialogKind = '' | 'qualification' | 'leave' | 'rehire'

const route = useRoute()
const router = useRouter()
const employeeId = String(route.params.employeeBusinessId ?? '')
const employee = ref<EmployeeView | null>(null)
const loading = ref(true)
const errorText = ref('')
const actionError = ref('')
const dialog = ref<DialogKind>('')
const dialogVisible = ref(false)
const saving = ref(false)

const today = new Date().toISOString().slice(0, 10)
const form = reactive({
  workTypeCode: 'MAKING',
  qualificationAction: 'grant' as 'grant' | 'revoke',
  effectiveDate: today,
  reason: '',
})

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
  loading.value = true
  errorText.value = ''
  try {
    employee.value = await workforceService.employee(employeeId)
  } catch (error) {
    errorText.value = userMessageOf(error, '员工详情加载失败')
  } finally {
    loading.value = false
  }
}

function openDialog(kind: Exclude<DialogKind, ''>): void {
  dialog.value = kind
  actionError.value = ''
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
  if (dialog.value === 'leave' && form.reason.trim() === '') {
    actionError.value = '请填写离职原因'
    return
  }
  saving.value = true
  actionError.value = ''
  try {
    if (dialog.value === 'qualification') {
      await workforceService.changeQualification(employeeId, form.qualificationAction, {
        workTypeCode: form.workTypeCode,
        effectiveDate: form.effectiveDate,
      })
      ElMessage.success(form.qualificationAction === 'grant' ? '资格已授予' : '资格已撤销')
    } else if (dialog.value === 'leave') {
      await workforceService.leave(employeeId, {
        effectiveDate: form.effectiveDate,
        reason: form.reason,
        expectedRowVersion: employee.value.rowVersion,
      })
      ElMessage.success('离职已登记')
    } else if (dialog.value === 'rehire') {
      await workforceService.rehire(employeeId, {
        effectiveDate: form.effectiveDate,
        expectedRowVersion: employee.value.rowVersion,
      })
      ElMessage.success('返聘已登记')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    actionError.value = userMessageOf(error, '操作失败')
  } finally {
    saving.value = false
  }
}

load()
</script>

<template>
  <section class="employee-detail">
    <PageState :loading="loading" :error="errorText" @retry="load">
      <template v-if="employee">
        <header class="head">
          <div>
            <h1>{{ employee.name }}</h1>
            <span class="meta">
              {{ employee.employeeNo }} · 更新于 <DateTimeText :value="employee.updatedAt" />
            </span>
          </div>
          <div class="actions">
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
          </div>
        </header>

        <el-descriptions :column="2" border>
          <el-descriptions-item label="电话">{{ employee.phone }}</el-descriptions-item>
          <el-descriptions-item label="在职状态">
            <CodeTag category="workforce.employmentStatus" :code="employee.employmentStatus" />
          </el-descriptions-item>
          <el-descriptions-item label="最近生效日">
            {{ employee.latestEffectiveDate }}
          </el-descriptions-item>
          <el-descriptions-item label="内容版本">{{
            employee.contentVersion
          }}</el-descriptions-item>
        </el-descriptions>

        <h2>工种资格</h2>
        <el-table :data="employee.qualifications" empty-text="尚未授予工种资格" border size="small">
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
      </template>
    </PageState>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="460px">
      <el-alert
        v-if="actionError"
        type="error"
        :closable="false"
        :title="actionError"
        class="alert"
      />
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
  </section>
</template>

<style scoped>
.employee-detail {
  background: #fff;
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
