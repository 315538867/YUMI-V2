<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useListQuery } from '@/platform/composables/useListQuery'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'
import type { CustomerStatus, CustomerView } from '@/modules/customer/contracts'
import { customerService } from '@/modules/customer/services/customerService'
import CodeTag from '@/shared/components/CodeTag.vue'
import ListPage from '@/shared/components/ListPage.vue'
import ListPager from '@/shared/components/ListPager.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const { loading, errorText, run } = useAsyncLoad()

const router = useRouter()
const rows = ref<CustomerView[]>([])
const activeCount = ref<number | null>(null)
const { filters, page, size, sizes, search, reset, goPage, changeSize } = useListQuery(
  { keyword: '', status: '' as CustomerStatus | '' },
  { onSearch: () => void load() },
)

async function load(): Promise<void> {
  await run(async () => {
    const [list, count] = await Promise.all([
      customerService.list(filters.keyword, filters.status, page.value, size.value),
      customerService.activeCount(),
    ])
    rows.value = list
    activeCount.value = count
  }, '客户列表加载失败')
}

void load()
</script>

<template>
  <ListPage
    class="customer-list"
    title="客户"
    hint="客户身份与联系方式"
    panel-title="客户列表"
    panel-hint="客户资料用于新订单自动带入"
    :meta="`启用客户 ${activeCount ?? '—'}`"
    :loading="loading"
    :error="errorText"
    @retry="load"
  >
    <template #filters>
      <el-input
        v-model="filters.keyword"
        placeholder="名称、编号、联系人或电话"
        clearable
        class="keyword"
        @keyup.enter="search"
      />
      <el-select
        v-model="filters.status"
        placeholder="全部状态"
        clearable
        class="status"
        aria-label="客户状态筛选"
      >
        <el-option label="启用" value="ACTIVE" />
        <el-option label="停用" value="INACTIVE" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </template>
    <template #actions>
      <PermissionButton
        permission="customer.manage"
        type="primary"
        @click="router.push('/customers/new')"
      >
        新增客户
      </PermissionButton>
    </template>
    <el-table :data="rows" empty-text="没有匹配的客户" border>
      <el-table-column prop="customerNo" label="客户编号" width="140" />
      <el-table-column prop="name" label="客户名称" min-width="160" />
      <el-table-column prop="contactName" label="联系人" width="120" />
      <el-table-column prop="phone" label="电话" width="150" />
      <el-table-column
        prop="defaultAddress"
        label="默认地址"
        min-width="220"
        show-overflow-tooltip
      />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <CodeTag category="common.status" :code="row.status" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="router.push(`/customers/${row.customerBusinessId}`)"
          >
            查看
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <template #pager>
      <ListPager
        :page="page"
        :size="size"
        :count="rows.length"
        :sizes="sizes"
        @update:page="goPage"
        @update:size="changeSize"
      />
    </template>
  </ListPage>
</template>

<style scoped>
.keyword {
  width: 280px;
}

.status {
  width: 130px;
}
</style>
