<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useAuthStore } from '@/platform/stores/auth'
import type { EmployeeView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import CodeTag from '@/shared/components/CodeTag.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const auth = useAuthStore()
const employees = ref<EmployeeView[]>([])
const loading = ref(false)
const errorText = ref('')
const dialog = ref<'create' | 'edit' | 'leave' | 'rehire' | 'qualification' | ''>('')
const dialogVisible = ref(false)
const active = ref<EmployeeView | null>(null)
const form = reactive({
  name: '',
  phone: '',
  effectiveDate: '2026-10-20',
  reason: '',
  workTypeCode: 'MAKING',
  qualificationAction: 'grant' as 'grant' | 'revoke',
})

const canManage = auth.hasPermission('workforce.employee.manage')
const dialogTitles = {
  create: '新增员工',
  edit: '编辑员工',
  leave: '登记离职',
  rehire: '登记返聘',
  qualification: '工种资格',
} as const
const dialogTitle = computed(() => (dialog.value === '' ? '' : dialogTitles[dialog.value]))

async function load(): Promise<void> {
  loading.value = true
  try {
    employees.value = await workforceService.employees()
  } catch (error) {
    errorText.value = userMessageOf(error, '员工列表加载失败')
  } finally {
    loading.value = false
  }
}

function open(kind: typeof dialog.value, employee: EmployeeView | null = null): void {
  active.value = employee
  form.name = employee?.name ?? ''
  form.phone = employee?.phone ?? ''
  form.reason = ''
  form.qualificationAction = 'grant'
  dialog.value = kind
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  try {
    if (dialog.value === 'create') {
      await workforceService.hire({
        name: form.name,
        phone: form.phone,
        effectiveDate: form.effectiveDate,
      })
      ElMessage.success('员工已入职')
    } else if (dialog.value === 'edit' && active.value !== null) {
      await workforceService.updateEmployee(active.value.employeeBusinessId, {
        name: form.name,
        phone: form.phone,
        expectedRowVersion: active.value.rowVersion,
      })
      ElMessage.success('员工资料已保存')
    } else if (dialog.value === 'leave' && active.value !== null) {
      await workforceService.leave(active.value.employeeBusinessId, {
        effectiveDate: form.effectiveDate,
        reason: form.reason,
        expectedRowVersion: active.value.rowVersion,
      })
      ElMessage.success('离职已登记')
    } else if (dialog.value === 'rehire' && active.value !== null) {
      await workforceService.rehire(active.value.employeeBusinessId, {
        effectiveDate: form.effectiveDate,
        expectedRowVersion: active.value.rowVersion,
      })
      ElMessage.success('返聘已登记')
    } else if (dialog.value === 'qualification' && active.value !== null) {
      await workforceService.changeQualification(
        active.value.employeeBusinessId,
        form.qualificationAction,
        { workTypeCode: form.workTypeCode, effectiveDate: form.effectiveDate },
      )
      ElMessage.success(form.qualificationAction === 'grant' ? '资格已授予' : '资格已撤销')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    errorText.value = userMessageOf(error, '操作失败')
  }
}

function leave(employee: EmployeeView): void {
  open('leave', employee)
}

onMounted(load)
</script>

<template>
  <section class="employee-page">
    <div class="toolbar">
      <h1>员工与资格</h1>
      <PermissionButton
        permission="workforce.employee.manage"
        type="primary"
        @click="open('create')"
        >新增员工</PermissionButton
      >
    </div>
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <el-table v-loading="loading" :data="employees" empty-text="暂无员工" border>
      <el-table-column prop="employeeNo" label="员工编号" width="130" />
      <el-table-column prop="name" label="姓名" width="130" />
      <el-table-column prop="phone" label="电话" width="150" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <CodeTag category="workforce.employmentStatus" :code="row.employmentStatus" />
        </template>
      </el-table-column>
      <el-table-column label="资格" min-width="200">
        <template #default="{ row }">
          <CodeTag
            v-for="item in row.qualifications"
            :key="item.workTypeCode"
            category="workforce.workType"
            :code="item.workTypeCode"
            class="tag"
          />
        </template>
      </el-table-column>
      <el-table-column prop="latestEffectiveDate" label="最近生效日" width="130" />
      <el-table-column v-if="canManage" label="操作" width="250" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open('edit', row)">编辑</el-button>
          <el-button link type="primary" @click="open('qualification', row)">资格</el-button>
          <el-button
            v-if="row.employmentStatus === 'EMPLOYED'"
            link
            type="warning"
            @click="leave(row)"
          >
            离职
          </el-button>
          <el-button v-else link type="success" @click="open('rehire', row)">返聘</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="460px">
      <el-form label-width="92px">
        <template v-if="dialog === 'create' || dialog === 'edit'">
          <el-form-item label="姓名" required><el-input v-model="form.name" /></el-form-item>
          <el-form-item label="电话" required><el-input v-model="form.phone" /></el-form-item>
        </template>
        <template v-if="dialog === 'leave' || dialog === 'rehire' || dialog === 'create'">
          <el-form-item label="生效日期">
            <el-date-picker v-model="form.effectiveDate" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
        </template>
        <el-form-item v-if="dialog === 'leave'" label="离职原因">
          <el-input v-model="form.reason" type="textarea" :rows="2" />
        </el-form-item>
        <template v-if="dialog === 'qualification'">
          <el-form-item label="工种">
            <el-select v-model="form.workTypeCode">
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
          <el-form-item label="生效日期">
            <el-date-picker v-model="form.effectiveDate" type="date" value-format="YYYY-MM-DD" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">确认</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.employee-page {
  background: #fff;
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

.alert {
  margin-bottom: 12px;
}
</style>
