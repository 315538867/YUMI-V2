<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useAsyncAction } from '@/platform/composables/useAsyncAction'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { CustomerChangeView, CustomerView } from '@/modules/customer/contracts'
import { customerService } from '@/modules/customer/services/customerService'
import CodeTag from '@/shared/components/CodeTag.vue'
import DateTimeText from '@/shared/components/DateTimeText.vue'
import DetailPage from '@/shared/components/DetailPage.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const route = useRoute()
const router = useRouter()
const customerId = String(route.params.customerBusinessId ?? '')
const customer = ref<CustomerView | null>(null)
const history = ref<CustomerChangeView[]>([])
const { loading, errorText, run } = useAsyncLoad()
const { run: runAction } = useAsyncAction()

async function load(): Promise<void> {
  await run(async () => {
    const [current, changes] = await Promise.all([
      customerService.get(customerId),
      customerService.history(customerId),
    ])
    customer.value = current
    history.value = changes
  }, '客户资料加载失败')
}

async function toggleStatus(): Promise<void> {
  if (customer.value === null) {
    return
  }
  const target = customer.value.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'
  try {
    await ElMessageBox.confirm(
      target === 'ACTIVE'
        ? '启用后该客户可以再次被新订单引用，历史订单不受影响。'
        : '停用后该客户不能再被新订单引用，历史订单和履约不受影响。',
      target === 'ACTIVE' ? '确认启用客户' : '确认停用客户',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await runAction(
    async () => {
      const updated = await customerService.changeStatus(
        customerId,
        target,
        customer.value!.rowVersion,
      )
      customer.value = updated
      history.value = await customerService.history(customerId)
    },
    {
      success: target === 'ACTIVE' ? '客户已启用' : '客户已停用；历史订单不受影响',
      fallback: '状态更新失败',
    },
  )
}

onMounted(load)
</script>

<template>
  <DetailPage
    class="customer-detail"
    :title="customer?.name ?? '客户详情'"
    :loading="loading"
    :error="errorText"
    @retry="load"
  >
    <template #meta>
      <template v-if="customer">
        {{ customer.customerNo }} · 更新于 <DateTimeText :value="customer.updatedAt" />
      </template>
    </template>
    <template #actions>
      <template v-if="customer">
        <PermissionButton
          v-if="customer.status === 'ACTIVE'"
          permission="order.create"
          type="primary"
          @click="router.push(`/orders/new?customer=${customerId}`)"
        >
          新建订单
        </PermissionButton>
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
      </template>
    </template>

    <template v-if="customer">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="联系人">{{ customer.contactName }}</el-descriptions-item>
        <el-descriptions-item label="电话">{{ customer.phone }}</el-descriptions-item>
        <el-descriptions-item label="默认地址" :span="2">
          {{ customer.defaultAddress }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <CodeTag category="common.status" :code="customer.status" />
        </el-descriptions-item>
        <el-descriptions-item label="内容版本">{{ customer.contentVersion }}</el-descriptions-item>
      </el-descriptions>
      <p class="yumi-hint">修改客户资料只影响新订单；停用不删除历史；收退款在订单内登记。</p>

      <h2>资料变化历史</h2>
      <el-table :data="history" empty-text="暂无变化记录" border size="small">
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <CodeTag category="customer.changeOperation" :code="row.operationType" />
          </template>
        </el-table-column>
        <el-table-column label="版本" width="120">
          <template #default="{ row }">{{ row.versionBefore }} → {{ row.versionAfter }}</template>
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
  </DetailPage>
</template>
