<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const active = computed(() => route.path)
const tabs = [
  { path: '/payroll/periods', label: '期间与核定' },
  { path: '/payroll/payments', label: '付款' },
  { path: '/payroll/adjustments', label: '人工调整' },
  { path: '/payroll/reviews', label: '只读复核' },
  { path: '/payroll/glue', label: '胶水月参考' },
]
</script>

<template>
  <el-tabs
    :model-value="active"
    class="payroll-tabs"
    @tab-change="(path: string | number) => router.push(String(path))"
  >
    <el-tab-pane v-for="tab in tabs" :key="tab.path" :label="tab.label" :name="tab.path" />
  </el-tabs>
  <RouterView />
</template>

<style scoped>
.payroll-tabs {
  background: var(--yumi-surface);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 0 16px;
  margin-bottom: 12px;
}
</style>
