<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUnsavedGuard } from '@/platform/composables/useUnsavedGuard'
import { useEditorForm } from '@/platform/composables/useEditorForm'
import type { EmployeeView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import CodeTag from '@/shared/components/CodeTag.vue'
import EditorPage from '@/shared/components/EditorPage.vue'
import FormErrorAlert from '@/shared/components/FormErrorAlert.vue'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'

const route = useRoute()
const router = useRouter()
const employeeId =
  typeof route.params.employeeBusinessId === 'string' ? route.params.employeeBusinessId : ''
const editing = employeeId !== ''
const { loading, errorText, run } = useAsyncLoad(editing)
const employee = ref<EmployeeView | null>(null)
const { saving, validationErrors, formAlert, fieldError, validate, submit } = useEditorForm(
  ['name', 'phone', 'effectiveDate'],
  '资料已被其他人修改，请刷新后重新核对；当前输入未被覆盖',
)

const form = reactive({
  name: '',
  phone: '',
  effectiveDate: new Date().toISOString().slice(0, 10),
})
const initialSnapshot = ref(JSON.stringify(form))
const isDirty = computed(() => JSON.stringify(form) !== initialSnapshot.value)
const { allowLeave } = useUnsavedGuard(() => isDirty.value)

function previewStatus(): string {
  return editing ? (employee.value?.employmentStatus ?? 'EMPLOYED') : 'EMPLOYED'
}

async function load(): Promise<void> {
  if (!editing) {
    return
  }
  await run(async () => {
    const current = await workforceService.employee(employeeId)
    employee.value = current
    form.name = current.name
    form.phone = current.phone
    form.effectiveDate = current.latestEffectiveDate
    initialSnapshot.value = JSON.stringify(form)
  }, '员工资料加载失败')
}

async function save(): Promise<void> {
  const valid = validate([
    { field: 'name', invalid: form.name.trim() === '', message: '请填写姓名' },
    { field: 'phone', invalid: form.phone.trim() === '', message: '请填写电话' },
  ])
  if (!valid) {
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
  await submit(action, {
    success: editing ? '员工资料已保存' : '员工已入职',
    fallback: '保存失败',
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
  <EditorPage
    :title="editing ? '编辑员工' : '新增员工'"
    preview-title="员工资料预览"
    preview-hint="编号由服务端生成；预览只用于核对。"
    :loading="loading"
    :error="errorText"
    @retry="load"
  >
    <template #actions>
      <el-button @click="cancel">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
    <el-form v-if="!loading" label-width="96px" @submit.prevent>
      <FormErrorAlert :error="formAlert" class="alert" />
      <el-form-item label="姓名" required :error="validationErrors.name || fieldError('name')">
        <el-input v-model="form.name" aria-label="姓名" />
      </el-form-item>
      <el-form-item label="电话" required :error="validationErrors.phone || fieldError('phone')">
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

    <template #preview>
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
    </template>
  </EditorPage>
</template>
