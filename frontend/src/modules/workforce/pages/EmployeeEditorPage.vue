<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { isErrorType, userMessageOf } from '@/platform/services/errorMessage'
import { useUnsavedGuard } from '@/platform/composables/useUnsavedGuard'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import type { EmployeeView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import CodeTag from '@/shared/components/CodeTag.vue'
import PageState from '@/shared/components/PageState.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

const route = useRoute()
const router = useRouter()
const employeeId =
  typeof route.params.employeeBusinessId === 'string' ? route.params.employeeBusinessId : ''
const editing = employeeId !== ''
const employee = ref<EmployeeView | null>(null)
const loading = ref(editing)
const errorText = ref('')
const conflictError = ref('')
const validationErrors = reactive<Record<string, string>>({})
const { loading: saving, fieldError, otherFieldErrors, run } = useAsyncAction()

const form = reactive({
  name: '',
  phone: '',
  effectiveDate: new Date().toISOString().slice(0, 10),
})
const initialSnapshot = ref(JSON.stringify(form))
const isDirty = computed(() => JSON.stringify(form) !== initialSnapshot.value)
const { allowLeave } = useUnsavedGuard(() => isDirty.value)
const formAlert = computed(
  () => conflictError.value || otherFieldErrors('name', 'phone', 'effectiveDate').join('；'),
)

function previewStatus(): string {
  return editing ? (employee.value?.employmentStatus ?? 'EMPLOYED') : 'EMPLOYED'
}

async function load(): Promise<void> {
  if (!editing) {
    return
  }
  loading.value = true
  errorText.value = ''
  try {
    const current = await workforceService.employee(employeeId)
    employee.value = current
    form.name = current.name
    form.phone = current.phone
    form.effectiveDate = current.latestEffectiveDate
    initialSnapshot.value = JSON.stringify(form)
  } catch (error) {
    errorText.value = userMessageOf(error, '员工资料加载失败')
  } finally {
    loading.value = false
  }
}

function validate(): boolean {
  for (const key of Object.keys(validationErrors)) {
    delete validationErrors[key]
  }
  if (form.name.trim() === '') {
    validationErrors.name = '请填写姓名'
  }
  if (form.phone.trim() === '') {
    validationErrors.phone = '请填写电话'
  }
  return Object.keys(validationErrors).length === 0
}

async function save(): Promise<void> {
  conflictError.value = ''
  if (!validate()) {
    return
  }
  const action =
    editing && employee.value !== null
      ? () =>
          workforceService.updateEmployee(employeeId, {
            name: form.name,
            phone: form.phone,
            expectedRowVersion: employee.value!.rowVersion,
          })
      : () => workforceService.hire({ ...form })
  await run(action, {
    success: editing ? '员工资料已保存' : '员工已入职',
    fallback: '保存失败',
    handled: (error) => {
      if (isErrorType(error, 'CONCURRENT_CONFLICT')) {
        conflictError.value = '资料已被其他人修改，请刷新后重新核对；当前输入未被覆盖'
        return true
      }
      return false
    },
    onSuccess: async (saved) => {
      allowLeave()
      await router.push(
        editing
          ? `/workforce/employees/${employeeId}`
          : `/workforce/employees/${saved.employeeBusinessId}`,
      )
    },
  })
}

function cancel(): void {
  void router.push(editing ? `/workforce/employees/${employeeId}` : '/workforce/employees')
}

onMounted(load)
</script>

<template>
  <WorkspacePage class="employee-editor">
    <header class="head">
      <h1>{{ editing ? '编辑员工' : '新增员工' }}</h1>
      <div>
        <el-button @click="cancel">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </header>
    <PageState :loading="loading" :error="errorText" @retry="load">
      <div class="editor-grid">
        <el-form v-if="!loading" label-width="96px" class="form" @submit.prevent>
          <el-alert
            v-if="formAlert"
            type="error"
            :closable="false"
            :title="formAlert"
            class="alert"
          />
          <el-form-item label="姓名" required :error="validationErrors.name || fieldError('name')">
            <el-input v-model="form.name" aria-label="姓名" />
          </el-form-item>
          <el-form-item
            label="电话"
            required
            :error="validationErrors.phone || fieldError('phone')"
          >
            <el-input v-model="form.phone" aria-label="电话" />
          </el-form-item>
          <el-form-item
            label="生效日期"
            :error="validationErrors.effectiveDate || fieldError('effectiveDate')"
          >
            <el-date-picker
              v-model="form.effectiveDate"
              type="date"
              value-format="YYYY-MM-DD"
              :disabled="editing"
              aria-label="生效日期"
            />
          </el-form-item>
        </el-form>

        <aside class="preview">
          <h2>员工资料预览</h2>
          <dl>
            <dt>姓名</dt>
            <dd>{{ form.name || '未填写' }}</dd>
            <dt>电话</dt>
            <dd>{{ form.phone || '未填写' }}</dd>
            <dt>生效日期</dt>
            <dd>{{ form.effectiveDate || '未填写' }}</dd>
            <dt>在职状态</dt>
            <dd><CodeTag category="workforce.employmentStatus" :code="previewStatus()" /></dd>
          </dl>
          <p class="hint">编号由服务端生成；预览只用于核对。</p>
        </aside>
      </div>
    </PageState>
  </WorkspacePage>
</template>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

h1 {
  margin: 0;
  font-size: 18px;
}

h2 {
  margin: 0 0 10px;
  font-size: 16px;
}

.editor-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 16px;
}

.form {
  max-width: 720px;
}

.preview {
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 12px 16px;
  background: var(--el-fill-color-lighter);
  align-self: start;
}

.preview dl {
  display: grid;
  grid-template-columns: 84px 1fr;
  gap: 8px 12px;
  margin: 0;
}

.preview dt {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.preview dd {
  margin: 0;
  font-size: 13px;
  word-break: break-all;
}

.hint {
  margin: 12px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.alert {
  margin-bottom: 12px;
}
</style>
