<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useAuthStore } from '@/platform/stores/auth'
import type { CustomerChangeView, CustomerView } from '@/modules/customer/contracts'
import { customerService } from '@/modules/customer/services/customerService'

const route = useRoute()
const auth = useAuthStore()
const customerId = String(route.params.customerBusinessId ?? '')
const customer = ref<CustomerView | null>(null)
const history = ref<CustomerChangeView[]>([])
const loading = ref(false)
const errorText = ref('')
const editing = ref(false)
const saving = ref(false)
const editError = ref('')
const form = reactive({ name: '', contactName: '', phone: '', defaultAddress: '' })

const canManage = auth.hasPermission('customer.manage')

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const [current, changes] = await Promise.all([
      customerService.get(customerId),
      customerService.history(customerId),
    ])
    customer.value = current
    history.value = changes
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

function startEdit(): void {
  if (customer.value === null) {
    return
  }
  editing.value = true
  editError.value = ''
  Object.assign(form, {
    name: customer.value.name,
    contactName: customer.value.contactName,
    phone: customer.value.phone,
    defaultAddress: customer.value.defaultAddress,
  })
}

async function save(): Promise<void> {
  if (customer.value === null) {
    return
  }
  saving.value = true
  editError.value = ''
  try {
    customer.value = await customerService.update(customerId, {
      ...form,
      expectedRowVersion: customer.value.rowVersion,
    })
    editing.value = false
    ElMessage.success('客户资料已保存')
    history.value = await customerService.history(customerId)
  } catch (error) {
    editError.value =
      error instanceof Error && 'type' in error && error.type === 'CONCURRENT_CONFLICT'
        ? '资料已被其他人修改，请刷新后重新核对；当前输入未被覆盖'
        : userMessageOf(error, '保存失败')
  } finally {
    saving.value = false
  }
}

async function toggleStatus(): Promise<void> {
  if (customer.value === null) {
    return
  }
  const target = customer.value.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  try {
    customer.value = await customerService.changeStatus(
      customerId,
      target,
      customer.value.rowVersion,
    )
    history.value = await customerService.history(customerId)
    ElMessage.success(target === 'ACTIVE' ? '客户已启用' : '客户已停用；历史订单不受影响')
  } catch (error) {
    editError.value = userMessageOf(error, '状态更新失败')
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading" class="customer-detail">
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" />
    <template v-if="customer">
      <header class="head">
        <div>
          <h1>{{ customer.name }}</h1>
          <span class="meta">{{ customer.customerNo }} · 更新于 {{ customer.updatedAt }}</span>
        </div>
        <div v-if="canManage" class="actions">
          <el-button v-if="!editing" @click="startEdit">编辑资料</el-button>
          <el-button
            :type="customer.status === 'ACTIVE' ? 'warning' : 'success'"
            @click="toggleStatus"
          >
            {{ customer.status === 'ACTIVE' ? '停用客户' : '启用客户' }}
          </el-button>
        </div>
      </header>

      <el-alert
        v-if="editError"
        type="error"
        :closable="false"
        :title="editError"
        class="alert"
      />

      <el-descriptions v-if="!editing" :column="2" border>
        <el-descriptions-item label="联系人">{{ customer.contactName }}</el-descriptions-item>
        <el-descriptions-item label="电话">{{ customer.phone }}</el-descriptions-item>
        <el-descriptions-item label="默认地址" :span="2">
          {{ customer.defaultAddress }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="customer.status === 'ACTIVE' ? 'success' : 'info'">
            {{ customer.status === 'ACTIVE' ? '启用' : '停用' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="内容版本">{{ customer.contentVersion }}</el-descriptions-item>
      </el-descriptions>

      <el-form v-else label-width="92px" class="edit-form">
        <el-form-item label="客户名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="联系人" required>
          <el-input v-model="form.contactName" />
        </el-form-item>
        <el-form-item label="电话" required>
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item label="默认地址">
          <el-input v-model="form.defaultAddress" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button @click="editing = false">取消</el-button>
        </el-form-item>
      </el-form>

      <h2>资料变化历史</h2>
      <el-table :data="history" empty-text="暂无变化记录" border size="small">
        <el-table-column prop="operationType" label="操作" width="120" />
        <el-table-column label="版本" width="120">
          <template #default="{ row }">{{ row.versionBefore }} → {{ row.versionAfter }}</template>
        </el-table-column>
        <el-table-column prop="changedFieldsJson" label="变更字段" min-width="240" show-overflow-tooltip />
        <el-table-column prop="reasonText" label="原因" min-width="160" />
        <el-table-column prop="occurredAt" label="时间" width="200" />
      </el-table>
    </template>
  </section>
</template>

<style scoped>
.customer-detail {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 20px;
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
}

h1 {
  margin: 0 0 4px;
  font-size: 20px;
}

.meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.actions {
  display: flex;
  gap: 8px;
}

.alert {
  margin-bottom: 12px;
}

.edit-form {
  max-width: 560px;
}

h2 {
  margin: 24px 0 10px;
  font-size: 16px;
}
</style>
