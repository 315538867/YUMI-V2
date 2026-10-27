<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { CustomerChangeView, CustomerView } from '@/modules/customer/contracts'
import { customerService } from '@/modules/customer/services/customerService'
import CodeTag from '@/shared/components/CodeTag.vue'
import DateTimeText from '@/shared/components/DateTimeText.vue'
import PageState from '@/shared/components/PageState.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

const route = useRoute()
const router = useRouter()
const customerId = String(route.params.customerBusinessId ?? '')
const customer = ref<CustomerView | null>(null)
const history = ref<CustomerChangeView[]>([])
const loading = ref(true)
const errorText = ref('')
const actionError = ref('')

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
  } catch (error) {
    errorText.value = userMessageOf(error, '客户资料加载失败')
  } finally {
    loading.value = false
  }
}

async function toggleStatus(): Promise<void> {
  if (customer.value === null) {
    return
  }
  const target = customer.value.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  actionError.value = ''
  try {
    customer.value = await customerService.changeStatus(
      customerId,
      target,
      customer.value.rowVersion,
    )
    history.value = await customerService.history(customerId)
    ElMessage.success(target === 'ACTIVE' ? '客户已启用' : '客户已停用；历史订单不受影响')
  } catch (error) {
    actionError.value = userMessageOf(error, '状态更新失败')
  }
}

onMounted(load)
</script>

<template>
  <WorkspacePage class="customer-detail">
    <PageState :loading="loading" :error="errorText" @retry="load">
      <template v-if="customer">
        <header class="head">
          <div>
            <h1>{{ customer.name }}</h1>
            <span class="meta"
              >{{ customer.customerNo }} · 更新于 <DateTimeText :value="customer.updatedAt"
            /></span>
          </div>
          <div class="actions">
            <PermissionButton
              permission="customer.manage"
              @click="router.push(`/customers/${customerId}/edit`)"
            >
              编辑资料
            </PermissionButton>
            <PermissionButton
              permission="customer.manage"
              :type="customer.status === 'ACTIVE' ? 'warning' : 'success'"
              @click="toggleStatus"
            >
              {{ customer.status === 'ACTIVE' ? '停用客户' : '启用客户' }}
            </PermissionButton>
          </div>
        </header>

        <el-alert
          v-if="actionError"
          type="error"
          :closable="false"
          :title="actionError"
          class="alert"
        />

        <el-descriptions :column="2" border>
          <el-descriptions-item label="联系人">{{ customer.contactName }}</el-descriptions-item>
          <el-descriptions-item label="电话">{{ customer.phone }}</el-descriptions-item>
          <el-descriptions-item label="默认地址" :span="2">
            {{ customer.defaultAddress }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <CodeTag category="common.status" :code="customer.status" />
          </el-descriptions-item>
          <el-descriptions-item label="内容版本">{{
            customer.contentVersion
          }}</el-descriptions-item>
        </el-descriptions>

        <h2>资料变化历史</h2>
        <el-table :data="history" empty-text="暂无变化记录" border size="small">
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <CodeTag category="customer.changeOperation" :code="row.operationType" />
            </template>
          </el-table-column>
          <el-table-column label="版本" width="120">
            <template #default="{ row }">
              {{ row.versionBefore }} → {{ row.versionAfter }}
            </template>
          </el-table-column>
          <el-table-column
            prop="changedFieldsJson"
            label="变更字段"
            min-width="240"
            show-overflow-tooltip
          />
          <el-table-column prop="reasonText" label="原因" min-width="160" />
          <el-table-column label="时间" width="200">
            <template #default="{ row }"><DateTimeText :value="row.occurredAt" /></template>
          </el-table-column>
        </el-table>
      </template>
    </PageState>
  </WorkspacePage>
</template>

<style scoped>
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

.alert {
  margin-bottom: 12px;
}

h2 {
  margin: 24px 0 10px;
  font-size: 16px;
}
</style>
