<script setup lang="ts">
import ModuleTabsPage from '@/shared/components/ModuleTabsPage.vue'

const tabs = [
  { path: '/settings/accounts', label: '账号与角色' },
  { path: '/settings/glue', label: '胶水配置' },
  { path: '/settings/audit', label: '审计检索' },
  { path: '/settings/tasks', label: '提交后任务' },
]
</script>

<template>
  <ModuleTabsPage title="设置与账号" hint="账号角色、胶水配置、审计检索与提交后任务" :tabs="tabs" />
</template>
