<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import ProductionOverviewCard from '@/modules/production/pages/ProductionOverviewCard.vue'

const route = useRoute()
const router = useRouter()
const active = computed(() => route.path)
const workspaceRoutes = new Set([
  'production-schedule',
  'production-demands',
  'production-trace',
  'production-quality',
  'production-corrections',
  'production-defect-reasons',
])
const isWorkspace = computed(() => workspaceRoutes.has(String(route.name)))
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
  <template v-if="isWorkspace">
    <header class="yumi-page-heading">
      <div class="yumi-page-heading__copy">
        <h1 class="yumi-page-heading__title">生产作业</h1>
        <span class="yumi-page-heading__hint">整体进度、周排班与核验</span>
      </div>
    </header>
    <ProductionOverviewCard />
    <el-tabs
      :model-value="active"
      class="yumi-module-tabs"
      @tab-change="(path: string | number) => router.push(String(path))"
    >
      <el-tab-pane v-for="tab in tabs" :key="tab.path" :label="tab.label" :name="tab.path" />
    </el-tabs>
  </template>
  <RouterView />
</template>
