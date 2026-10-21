<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const active = computed(() => route.path)
const tabs = [
  { path: '/finance/daily-cash', label: '日常收支' },
  { path: '/finance/cash-history', label: '现金流水' },
  { path: '/finance/cost-entries', label: '成本明细' },
  { path: '/finance/cost-failures', label: '归集失败' },
  { path: '/finance/cost-allocation', label: '成本分摊' },
]
</script>

<template>
  <el-tabs :model-value="active" class="finance-tabs" @tab-change="(path: string | number) => router.push(String(path))">
    <el-tab-pane v-for="tab in tabs" :key="tab.path" :label="tab.label" :name="tab.path" />
  </el-tabs>
  <RouterView />
</template>

<style scoped>
.finance-tabs {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 0 16px;
  margin-bottom: 12px;
}
</style>
