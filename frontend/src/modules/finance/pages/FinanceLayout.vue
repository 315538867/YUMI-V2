<script setup lang="ts">
import ModuleTabsPage from '@/shared/components/ModuleTabsPage.vue'

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
  <ModuleTabsPage
    title="现金与成本"
    hint="按月看实际收支与成本；工资逐人核定，付款单独登记"
    :tabs="tabs"
  />
</template>
