<script setup lang="ts">
import ModuleTabsPage from '@/shared/components/ModuleTabsPage.vue'

const tabs = [
  { path: '/reporting/monthly', label: '月汇总' },
  { path: '/reporting/explorer', label: '主题报表' },
  { path: '/reporting/reconciliation', label: '来源对账' },
  { path: '/reporting/rebuild', label: '影子重建' },
]
</script>

<template>
  <ModuleTabsPage title="报表" hint="月度、主题、来源对账与影子重建" :tabs="tabs" />
</template>
