<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import UiSectionTabs from '@/shared/ui/UiSectionTabs.vue'

const route = useRoute()
const router = useRouter()

const tabs = [
  { key: '/production/overview', label: '总览' },
  { key: '/production/schedule', label: '排班' },
  { key: '/production/demands', label: '需求缺口' },
  { key: '/production/trace', label: '订单追溯' },
  { key: '/production/quality', label: '质量与责任' },
  { key: '/production/corrections', label: '事实更正' },
  { key: '/production/defect-reasons', label: '缺陷原因' },
]

const workspaceRoutes = new Set([
  'production-overview',
  'production-schedule',
  'production-demands',
  'production-trace',
  'production-quality',
  'production-corrections',
  'production-defect-reasons',
])

const isWorkspace = computed(() => workspaceRoutes.has(String(route.name)))
const activeTab = computed(
  () => tabs.find((tab) => route.path.startsWith(tab.key))?.key ?? '/production/schedule',
)

function selectTab(key: string): void {
  void router.push(key)
}
</script>

<template>
  <div v-if="isWorkspace" class="production-layout">
    <UiSectionTabs :model-value="activeTab" :items="tabs" @update:model-value="selectTab" />
    <div class="production-content"><RouterView /></div>
  </div>
  <RouterView v-else />
</template>

<style scoped>
.production-layout {
  display: flex;
  flex-direction: column;
  gap: var(--yumi-ds-space-md);
  height: calc(
    100vh - var(--yumi-ds-topnav-height) - var(--yumi-ds-space-xl) - var(--yumi-ds-space-5xl)
  );
  min-height: 0;
}

.production-content {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
}
</style>
