<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useAuthStore } from '@/platform/stores/auth'
import type { CustomerStatus, CustomerView } from '@/modules/customer/contracts'
import { customerService } from '@/modules/customer/services/customerService'

const router = useRouter()
const auth = useAuthStore()
const rows = ref<CustomerView[]>([])
const loading = ref(false)
const errorText = ref('')
const filters = reactive<{ keyword: string; status: CustomerStatus | '' }>({
  keyword: '',
  status: '',
})
const createVisible = ref(false)
const creating = ref(false)
const createError = ref('')
const form = reactive({ name: '', contactName: '', phone: '', defaultAddress: '' })

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    rows.value = await customerService.list(filters.keyword, filters.status)
  } catch (error) {
    errorText.value = userMessageOf(error, '客户列表加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  createError.value = ''
  Object.assign(form, { name: '', contactName: '', phone: '', defaultAddress: '' })
  createVisible.value = true
}

async function create(): Promise<void> {
  if (form.name.trim() === '' || form.contactName.trim() === '' || form.phone.trim() === ''
      || form.defaultAddress.trim() === '') {
    createError.value = '请填写客户名称、联系人、电话和默认收货地址'
    return
  }
  creating.value = true
  createError.value = ''
  try {
    const created = await customerService.create({ ...form })
    createVisible.value = false
    ElMessage.success('客户已创建')
    await router.push(`/customers/${created.customerBusinessId}`)
  } catch (error) {
    createError.value = userMessageOf(error, '客户创建失败')
  } finally {
    creating.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="customer-list">
    <h1>客户</h1>
    <div class="toolbar">
      <el-input
        v-model="filters.keyword"
        placeholder="名称、编号、联系人或电话"
        clearable
        class="keyword"
        @keyup.enter="load"
      />
      <el-select v-model="filters.status" placeholder="全部状态" clearable class="status">
        <el-option label="启用" value="ACTIVE" />
        <el-option label="停用" value="INACTIVE" />
      </el-select>
      <el-button type="primary" @click="load">查询</el-button>
      <el-button v-if="auth.hasPermission('customer.manage')" @click="openCreate">
        新增客户
      </el-button>
    </div>

    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />

    <el-table v-loading="loading" :data="rows" empty-text="暂无客户" border>
      <el-table-column prop="customerNo" label="客户编号" width="140" />
      <el-table-column prop="name" label="客户名称" min-width="160" />
      <el-table-column prop="contactName" label="联系人" width="120" />
      <el-table-column prop="phone" label="电话" width="150" />
      <el-table-column prop="defaultAddress" label="默认地址" min-width="220" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ACTIVE' ? 'success' : 'info'">
            {{ row.status === 'ACTIVE' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="router.push(`/customers/${row.customerBusinessId}`)">
            查看
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="createVisible" title="新增客户" width="480px">
      <el-alert
        v-if="createError"
        type="error"
        :closable="false"
        :title="createError"
        class="alert"
      />
      <el-form label-width="92px">
        <el-form-item label="客户名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="联系人" required>
          <el-input v-model="form.contactName" />
        </el-form-item>
        <el-form-item label="电话" required>
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item label="默认地址" required>
          <el-input v-model="form.defaultAddress" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="create">创建</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.customer-list h1 { margin: 0 0 12px; font-size: 18px; }

.customer-list {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.keyword {
  width: 280px;
}

.status {
  width: 130px;
}

.alert {
  margin-bottom: 12px;
}
</style>
