<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { isErrorType, userMessageOf } from '@/platform/services/errorMessage'
import type { EmployeeView } from '@/modules/workforce/contracts'
import { workforceService } from '@/modules/workforce/services/workforceService'
import PageState from '@/shared/components/PageState.vue'

const route = useRoute()
const router = useRouter()
const employeeId = String(route.params.employeeBusinessId ?? '')
const employee = ref<EmployeeView | null>(null)
const loading = ref(true)
const saving = ref(false)
const errorText = ref('')
const formError = ref('')

const form = reactive({ name: '', phone: '' })

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const current = await workforceService.employee(employeeId)
    employee.value = current
    form.name = current.name
    form.phone = current.phone
  } catch (error) {
    errorText.value = userMessageOf(error, '员工资料加载失败')
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  if (employee.value === null) {
    return
  }
  if (form.name.trim() === '' || form.phone.trim() === '') {
    formError.value = '请填写姓名和电话'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    await workforceService.updateEmployee(employeeId, {
      name: form.name,
      phone: form.phone,
      expectedRowVersion: employee.value.rowVersion,
    })
    ElMessage.success('员工资料已保存')
    await router.push(`/workforce/employees/${employeeId}`)
  } catch (error) {
    formError.value = isErrorType(error, 'CONCURRENT_CONFLICT')
      ? '资料已被其他人修改，请刷新后重新核对；当前输入未被覆盖'
      : userMessageOf(error, '保存失败')
  } finally {
    saving.value = false
  }
}

function cancel(): void {
  void router.push(`/workforce/employees/${employeeId}`)
}

onMounted(load)
</script>

<template>
  <section class="employee-editor">
    <h1>编辑员工</h1>
    <PageState :loading="loading" :error="errorText" @retry="load">
      <el-form v-if="!loading" label-width="96px" class="form" @submit.prevent>
        <el-alert
          v-if="formError"
          type="error"
          :closable="false"
          :title="formError"
          class="alert"
        />
        <el-form-item label="姓名" required>
          <el-input v-model="form.name" aria-label="姓名" />
        </el-form-item>
        <el-form-item label="电话" required>
          <el-input v-model="form.phone" aria-label="电话" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button @click="cancel">取消</el-button>
        </el-form-item>
      </el-form>
    </PageState>
  </section>
</template>

<style scoped>
.employee-editor {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 16px;
}

.employee-editor h1 {
  margin: 0 0 12px;
  font-size: 18px;
}

.form {
  max-width: 720px;
}

.alert {
  margin-bottom: 12px;
}
</style>
