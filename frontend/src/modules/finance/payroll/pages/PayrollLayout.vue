<script setup lang="ts">
import ModuleTabsPage from '@/shared/components/ModuleTabsPage.vue'

const tabs = [
  { path: '/payroll/periods', label: '期间与核定' },
  { path: '/payroll/payments', label: '付款' },
  { path: '/payroll/adjustments', label: '人工调整' },
  { path: '/payroll/reviews', label: '只读复核' },
  { path: '/payroll/glue', label: '胶水月参考' },
]
</script>

<template>
  <ModuleTabsPage title="工资" hint="期间核定、付款、人工调整与只读复核" :tabs="tabs" />
</template>
