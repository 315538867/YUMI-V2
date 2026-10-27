<script setup lang="ts">
import { ref } from 'vue'
import { useListQuery } from '@/platform/composables/useListQuery'
import type { AuditEntry } from '@/modules/platform-admin/services/administrationService'
import { administrationService } from '@/modules/platform-admin/services/administrationService'
import CodeTag from '@/shared/components/CodeTag.vue'
import CodeLabel from '@/shared/components/CodeLabel.vue'
import DateTimeText from '@/shared/components/DateTimeText.vue'
import ListPage from '@/shared/components/ListPage.vue'
import { useAsyncLoad } from '@/platform/composables/useAsyncLoad'

const { loading, errorText, run } = useAsyncLoad()

const entries = ref<AuditEntry[]>([])
const { filters, search, reset } = useListQuery(
  { targetType: 'ORDER', targetBusinessId: '' },
  { paged: false, onSearch: () => void load() },
)

async function load(): Promise<void> {
  if (filters.targetBusinessId.trim() === '') {
    entries.value = []
    errorText.value = ''
    return
  }
  await run(async () => {
    entries.value = await administrationService.auditByTarget(
      filters.targetType,
      filters.targetBusinessId.trim(),
    )
  }, '审计检索失败')
}
</script>

<template>
  <ListPage
    class="audit-page"
    title="审计检索"
    :loading="loading"
    :error="errorText"
    :empty="!loading && errorText === '' && entries.length === 0"
    empty-text="选择目标类型并输入标识后检索"
    @retry="load"
  >
    <template #filters>
      <el-select v-model="filters.targetType" class="type" aria-label="审计目标类型">
        <el-option label="订单" value="ORDER" />
        <el-option label="客户" value="CUSTOMER" />
        <el-option label="商品" value="PRODUCT" />
        <el-option label="员工" value="EMPLOYEE" />
        <el-option label="人员管理" value="WORKFORCE" />
        <el-option label="账号" value="ACCOUNT" />
        <el-option label="售后案例" value="AFTER_SALES_CASE" />
      </el-select>
      <el-input
        v-model="filters.targetBusinessId"
        placeholder="目标业务标识"
        class="input"
        aria-label="目标业务标识"
        @keyup.enter="search"
      />
      <el-button type="primary" @click="search">检索</el-button>
      <el-button @click="reset">重置</el-button>
    </template>
    <template #notice>
      <el-alert
        type="info"
        :closable="false"
        title="审计只读；错误通过追加关联说明更正，不修改或删除原记录。"
      />
    </template>
    <el-table :data="entries" border empty-text="暂无审计记录">
      <el-table-column label="时间" width="230">
        <template #default="{ row }"><DateTimeText :value="row.occurredAt" /></template>
      </el-table-column>
      <el-table-column label="模块" width="160">
        <template #default="{ row }"
          ><CodeTag category="platform.auditModule" :code="row.moduleCode" fallback="其他模块"
        /></template>
      </el-table-column>
      <el-table-column label="动作" min-width="190">
        <template #default="{ row }"
          ><CodeLabel category="platform.auditAction" :code="row.actionCode" fallback="其他操作"
        /></template>
      </el-table-column>
      <el-table-column label="结果" width="110">
        <template #default="{ row }"
          ><CodeTag category="platform.auditResult" :code="row.resultCode"
        /></template>
      </el-table-column>
      <el-table-column prop="accountBusinessId" label="账号" min-width="160" />
      <el-table-column prop="requestId" label="请求编号" min-width="200" />
      <el-table-column label="详情" min-width="200">
        <template #default="{ row }">{{ JSON.stringify(row.detail) }}</template>
      </el-table-column>
    </el-table>
  </ListPage>
</template>

<style scoped>
.type {
  width: 150px;
}
.input {
  width: 280px;
}
</style>
