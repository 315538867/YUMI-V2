<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUnsavedGuard } from '@/platform/composables/useUnsavedGuard'
import { useEditorForm } from '@/platform/composables/useEditorForm'
import type { CustomerView } from '@/modules/customer/contracts'
import { customerService } from '@/modules/customer/services/customerService'
import CodeTag from '@/shared/components/CodeTag.vue'
import EditorPage from '@/shared/components/EditorPage.vue'
import FormErrorAlert from '@/shared/components/FormErrorAlert.vue'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'

const route = useRoute()
const router = useRouter()
const customerId =
  typeof route.params.customerBusinessId === 'string' ? route.params.customerBusinessId : ''
const editing = customerId !== ''
const { loading, errorText, run } = useAsyncLoad(editing)
const customer = ref<CustomerView | null>(null)
const { saving, validationErrors, formAlert, fieldError, validate, submit } = useEditorForm(
  ['name', 'contactName', 'phone', 'defaultAddress'],
  '资料已被其他人修改，请刷新后重新核对；当前输入未被覆盖',
)

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
  await run(async () => {
    const current = await customerService.get(customerId)
    customer.value = current
    Object.assign(form, {
      name: current.name,
      contactName: current.contactName,
      phone: current.phone,
      defaultAddress: current.defaultAddress,
    })
    initialSnapshot.value = JSON.stringify(form)
  }, '客户资料加载失败')
}

async function save(): Promise<void> {
  const valid = validate([
    { field: 'name', invalid: form.name.trim() === '', message: '请填写客户名称' },
    { field: 'contactName', invalid: form.contactName.trim() === '', message: '请填写联系人' },
    { field: 'phone', invalid: form.phone.trim() === '', message: '请填写电话' },
    {
      field: 'defaultAddress',
      invalid: form.defaultAddress.trim() === '',
      message: '请填写默认收货地址',
    },
  ])
  if (!valid) {
    return
  }
  const action =
    editing && customer.value !== null
      ? () =>
          customerService.update(customerId, {
            ...form,
            expectedRowVersion: customer.value!.rowVersion,
          })
      : () => customerService.create({ ...form })
  await submit(action, {
    success: editing ? '客户资料已保存' : '客户已创建',
    fallback: '保存失败',
    onSuccess: async (saved) => {
      allowLeave()
      await router.push(
        editing ? `/customers/${customerId}` : `/customers/${saved.customerBusinessId}`,
      )
    },
  })
}

function cancel(): void {
  void router.push(editing ? `/customers/${customerId}` : '/customers')
}

onMounted(load)
</script>

<template>
  <EditorPage
    :title="editing ? '编辑客户' : '新建客户'"
    preview-title="客户资料预览"
    preview-hint="预览只用于核对，保存后以服务端结果为准。"
    :loading="loading"
    :error="errorText"
    @retry="load"
  >
    <template #actions>
      <el-button @click="cancel">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
    <el-form v-if="!loading" label-width="96px" @submit.prevent>
      <FormErrorAlert :error="formAlert" class="yumi-alert" />
      <h2>基本资料</h2>
      <el-form-item v-if="editing" label="客户编号">
        <el-input :model-value="customer?.customerNo ?? '—'" disabled />
      </el-form-item>
      <el-form-item label="客户名称" required :error="validationErrors.name || fieldError('name')">
        <el-input v-model="form.name" aria-label="客户名称" />
      </el-form-item>
      <el-form-item
        label="联系人"
        required
        :error="validationErrors.contactName || fieldError('contactName')"
      >
        <el-input v-model="form.contactName" aria-label="联系人" />
      </el-form-item>
      <el-form-item label="电话" required :error="validationErrors.phone || fieldError('phone')">
        <el-input v-model="form.phone" aria-label="电话" />
      </el-form-item>
      <h2>默认收货信息</h2>
      <el-form-item
        label="默认地址"
        required
        :error="validationErrors.defaultAddress || fieldError('defaultAddress')"
      >
        <el-input v-model="form.defaultAddress" type="textarea" :rows="3" aria-label="默认地址" />
      </el-form-item>
    </el-form>

    <template #preview>
      <dl class="yumi-info-grid">
        <dt v-if="editing">客户编号</dt>
        <dd v-if="editing">{{ customer?.customerNo ?? '—' }}</dd>
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
    </template>
  </EditorPage>
</template>
