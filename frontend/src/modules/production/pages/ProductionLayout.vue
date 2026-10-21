<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const active = computed(() => route.path)
const tabs = [
  { path: '/production/schedule', label: '排班' },
  { path: '/production/demands', label: '需求缺口' },
  { path: '/production/trace', label: '订单追溯' },
  { path: '/production/quality', label: '质量与责任' },
  { path: '/production/corrections', label: '事实更正' },
  { path: '/production/defect-reasons', label: '缺陷原因' },
]
</script>

<template>
  <el-tabs :model-value="active" class="production-tabs" @tab-change="(path: string | number) => router.push(String(path))">
    <el-tab-pane v-for="tab in tabs" :key="tab.path" :label="tab.label" :name="tab.path" />
  </el-tabs>
  <RouterView />
</template>

<style scoped>
.production-tabs {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 0 16px;
  margin-bottom: 12px;
}
</style>
