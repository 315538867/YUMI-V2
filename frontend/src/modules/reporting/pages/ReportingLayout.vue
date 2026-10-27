<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const active = computed(() => route.path)
const tabs = [
  { path: '/reporting/monthly', label: '月汇总' },
  { path: '/reporting/explorer', label: '主题报表' },
  { path: '/reporting/reconciliation', label: '来源对账' },
  { path: '/reporting/rebuild', label: '影子重建' },
]
</script>

<template>
  <el-tabs
    :model-value="active"
    class="reporting-tabs"
    @tab-change="(path: string | number) => router.push(String(path))"
  >
    <el-tab-pane v-for="tab in tabs" :key="tab.path" :label="tab.label" :name="tab.path" />
  </el-tabs>
  <RouterView />
</template>

<style scoped>
.reporting-tabs {
  background: var(--yumi-surface);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 0 16px;
  margin-bottom: 12px;
}
</style>
