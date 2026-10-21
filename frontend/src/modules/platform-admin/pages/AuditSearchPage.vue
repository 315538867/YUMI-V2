<script setup lang="ts">
import { ref } from 'vue'
import { userMessageOf } from '@/platform/services/errorMessage'
import type { AuditEntry } from '@/modules/platform-admin/services/administrationService'
import { administrationService } from '@/modules/platform-admin/services/administrationService'

const targetType = ref('ORDER')
const targetBusinessId = ref('')
const entries = ref<AuditEntry[]>([])
const errorText = ref('')

async function load(): Promise<void> {
  if (targetBusinessId.value.trim() === '') {
    errorText.value = '请输入目标业务标识'
    return
  }
  errorText.value = ''
  try {
    entries.value = await administrationService.auditByTarget(
      targetType.value,
      targetBusinessId.value.trim(),
    )
  } catch (error) {
    errorText.value = userMessageOf(error, '审计检索失败')
  }
}
</script>

<template>
  <section class="audit-page">
    <h1>审计检索</h1>
    <el-alert type="info" :closable="false" title="审计只读；错误通过追加关联说明更正，不修改或删除原记录。" class="alert" />
    <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
    <div class="toolbar">
      <el-select v-model="targetType" class="type">
        <el-option label="订单" value="ORDER" />
        <el-option label="客户" value="CUSTOMER" />
        <el-option label="商品" value="PRODUCT" />
        <el-option label="员工" value="EMPLOYEE" />
        <el-option label="人员管理" value="WORKFORCE" />
        <el-option label="账号" value="ACCOUNT" />
        <el-option label="售后案例" value="AFTER_SALES_CASE" />
      </el-select>
      <el-input v-model="targetBusinessId" placeholder="目标业务标识" class="input" @keyup.enter="load" />
      <el-button type="primary" @click="load">检索</el-button>
    </div>
    <el-table :data="entries" border empty-text="暂无审计记录">
      <el-table-column prop="occurredAt" label="时间" width="230" />
      <el-table-column prop="moduleCode" label="模块" width="160" />
      <el-table-column prop="actionCode" label="动作" min-width="190" />
      <el-table-column prop="resultCode" label="结果" width="110" />
      <el-table-column prop="accountBusinessId" label="账号" min-width="160" />
      <el-table-column prop="requestId" label="请求编号" min-width="200" />
      <el-table-column label="详情" min-width="200">
        <template #default="{ row }">{{ JSON.stringify(row.detail) }}</template>
      </el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.audit-page { background: #fff; border: 1px solid var(--el-border-color-light); border-radius: 8px; padding: 20px; }
h1 { margin: 0 0 12px; font-size: 18px; }
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; }
.type { width: 150px; }
.input { width: 280px; }
.alert { margin-bottom: 12px; }
</style>
