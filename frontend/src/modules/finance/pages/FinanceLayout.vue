<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const active = computed(() => route.path)
const tabs = [
  { path: '/finance/monthly', label: '月度汇总' },
  { path: '/finance/daily-cash', label: '资金收付' },
  { path: '/finance/cash-history', label: '现金流水' },
  { path: '/finance/cost-entries', label: '成本台账' },
  { path: '/finance/cost-allocation', label: '成本分摊' },
  { path: '/finance/cost-failures', label: '归集失败' },
  { path: '/payroll/periods', label: '工资结算' },
]
</script>

<template>
  <el-tabs
    :model-value="active"
    class="finance-tabs"
    @tab-change="(path: string | number) => router.push(String(path))"
  >
    <el-tab-pane v-for="tab in tabs" :key="tab.path" :label="tab.label" :name="tab.path" />
  </el-tabs>
  <RouterView />
</template>

<style scoped>
.finance-tabs {
  background: var(--yumi-surface);
  border: 1px solid var(--yumi-border-color);
  border-radius: var(--yumi-radius-lg);
  padding: 0 var(--yumi-space-4);
  margin-bottom: var(--yumi-space-3);
}
</style>
