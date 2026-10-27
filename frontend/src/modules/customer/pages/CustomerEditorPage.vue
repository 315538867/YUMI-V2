<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { isErrorType, userMessageOf } from '@/platform/services/errorMessage'
import { useUnsavedGuard } from '@/platform/composables/useUnsavedGuard'
import type { CustomerView } from '@/modules/customer/contracts'
import { customerService } from '@/modules/customer/services/customerService'
import CodeTag from '@/shared/components/CodeTag.vue'
import PageState from '@/shared/components/PageState.vue'

const route = useRoute()
const router = useRouter()
const customerId =
  typeof route.params.customerBusinessId === 'string' ? route.params.customerBusinessId : ''
const editing = customerId !== ''
const loading = ref(editing)
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
const initialSnapshot = ref(JSON.stringify(form))
const isDirty = computed(() => JSON.stringify(form) !== initialSnapshot.value)
const { allowLeave } = useUnsavedGuard(() => isDirty.value)

function previewStatus(): string {
  return editing ? (customer.value?.status ?? 'ACTIVE') : 'ACTIVE'
}

async function load(): Promise<void> {
  if (!editing) {
    return
  }
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
    initialSnapshot.value = JSON.stringify(form)
  } catch (error) {
    errorText.value = userMessageOf(error, '客户资料加载失败')
  } finally {
    loading.value = false
  }
}

async function save(): Promise<void> {
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
    if (editing && customer.value !== null) {
      await customerService.update(customerId, {
        ...form,
        expectedRowVersion: customer.value.rowVersion,
      })
      ElMessage.success('客户资料已保存')
      allowLeave()
      await router.push(`/customers/${customerId}`)
    } else {
      const created = await customerService.create({ ...form })
      ElMessage.success('客户已创建')
      allowLeave()
      await router.push(`/customers/${created.customerBusinessId}`)
    }
  } catch (error) {
    formError.value = isErrorType(error, 'CONCURRENT_CONFLICT')
      ? '资料已被其他人修改，请刷新后重新核对；当前输入未被覆盖'
      : userMessageOf(error, '保存失败')
  } finally {
    saving.value = false
  }
}

function cancel(): void {
  void router.push(editing ? `/customers/${customerId}` : '/customers')
}

onMounted(load)
</script>

<template>
  <section class="customer-editor">
    <header class="head">
      <h1>{{ editing ? '编辑客户' : '新建客户' }}</h1>
      <div>
        <el-button @click="cancel">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </header>
    <PageState :loading="loading" :error="errorText" @retry="load">
      <div class="editor-grid">
        <el-form v-if="!loading" label-width="96px" class="form" @submit.prevent>
          <el-alert
            v-if="formError"
            type="error"
            :closable="false"
            :title="formError"
            class="alert"
          />
          <h2>基本资料</h2>
          <el-form-item label="客户名称" required>
            <el-input v-model="form.name" aria-label="客户名称" />
          </el-form-item>
          <el-form-item label="联系人" required>
            <el-input v-model="form.contactName" aria-label="联系人" />
          </el-form-item>
          <el-form-item label="电话" required>
            <el-input v-model="form.phone" aria-label="电话" />
          </el-form-item>
          <h2>默认收货信息</h2>
          <el-form-item label="默认地址" required>
            <el-input
              v-model="form.defaultAddress"
              type="textarea"
              :rows="3"
              aria-label="默认地址"
            />
          </el-form-item>
        </el-form>

        <aside class="preview">
          <h2>客户资料预览</h2>
          <dl>
            <dt>客户名称</dt>
            <dd>{{ form.name || '未填写' }}</dd>
            <dt>联系人</dt>
            <dd>{{ form.contactName || '未填写' }}</dd>
            <dt>电话</dt>
            <dd>{{ form.phone || '未填写' }}</dd>
            <dt>默认地址</dt>
            <dd>{{ form.defaultAddress || '未填写' }}</dd>
            <dt>状态</dt>
            <dd><CodeTag category="common.status" :code="previewStatus()" /></dd>
          </dl>
          <p class="hint">预览只用于核对，保存后以服务端结果为准。</p>
        </aside>
      </div>
    </PageState>
  </section>
</template>

<style scoped>
.customer-editor {
  background: var(--yumi-surface);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 16px;
}

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
  margin: 16px 0 10px;
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

.preview h2 {
  margin-top: 0;
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
