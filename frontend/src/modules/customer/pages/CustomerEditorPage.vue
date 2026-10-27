<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { isErrorType, userMessageOf } from '@/platform/services/errorMessage'
import type { CustomerView } from '@/modules/customer/contracts'
import { customerService } from '@/modules/customer/services/customerService'
import PageState from '@/shared/components/PageState.vue'

const route = useRoute()
const router = useRouter()
const customerId = String(route.params.customerBusinessId ?? '')
const loading = ref(true)
const saving = ref(false)
const errorText = ref('')
const formError = ref('')
const customer = ref<CustomerView | null>(null)

const form = reactive({
  name: '',
  contactName: '',
  phone: '',
  defaultAddress: '',
})

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const current = await customerService.get(customerId)
    customer.value = current
    Object.assign(form, {
      name: current.name,
      contactName: current.contactName,
      phone: current.phone,
      defaultAddress: current.defaultAddress,
    })
  } catch (error) {
    errorText.value = userMessageOf(error, '客户资料加载失败')
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
  if (customer.value === null) {
    return
  }
  if (
    form.name.trim() === '' ||
    form.contactName.trim() === '' ||
    form.phone.trim() === '' ||
    form.defaultAddress.trim() === ''
  ) {
    formError.value = '请填写客户名称、联系人、电话和默认收货地址'
    return
  }
  saving.value = true
  formError.value = ''
  try {
    await customerService.update(customerId, {
      ...form,
      expectedRowVersion: customer.value.rowVersion,
    })
    ElMessage.success('客户资料已保存')
    await router.push(`/customers/${customerId}`)
  } catch (error) {
    formError.value = isErrorType(error, 'CONCURRENT_CONFLICT')
      ? '资料已被其他人修改，请刷新后重新核对；当前输入未被覆盖'
      : userMessageOf(error, '保存失败')
  } finally {
    saving.value = false
  }
}

function cancel(): void {
  void router.push(`/customers/${customerId}`)
}

onMounted(load)
</script>

<template>
  <section class="customer-editor">
    <h1>编辑客户</h1>
    <PageState :loading="loading" :error="errorText" @retry="load">
      <el-form v-if="!loading" label-width="96px" class="form" @submit.prevent>
        <el-alert
          v-if="formError"
          type="error"
          :closable="false"
          :title="formError"
          class="alert"
        />
        <el-form-item label="客户名称" required>
          <el-input v-model="form.name" aria-label="客户名称" />
        </el-form-item>
        <el-form-item label="联系人" required>
          <el-input v-model="form.contactName" aria-label="联系人" />
        </el-form-item>
        <el-form-item label="电话" required>
          <el-input v-model="form.phone" aria-label="电话" />
        </el-form-item>
        <el-form-item label="默认地址" required>
          <el-input v-model="form.defaultAddress" type="textarea" :rows="3" aria-label="默认地址" />
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
.customer-editor {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 16px;
}

.customer-editor h1 {
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
