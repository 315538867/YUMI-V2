<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import UiPageBar from '@/shared/ui/UiPageBar.vue'
import UiSectionTabs from '@/shared/ui/UiSectionTabs.vue'

const props = withDefaults(
  defineProps<{
    title: string
    hint?: string
    tabs: ReadonlyArray<{ path: string; label: string }>
  }>(),
  { hint: '' },
)

const route = useRoute()
const router = useRouter()
const items = computed(() => props.tabs.map((tab) => ({ key: tab.path, label: tab.label })))
const active = computed(() => route.path)

function selectTab(key: string): void {
  void router.push(key)
}
</script>

<template>
  <div class="module-tabs-page">
    <h1 class="yumi-sr-only">{{ title }}</h1>
    <UiPageBar :breadcrumb="[title]" :meta="hint">
      <template #actions><slot name="actions" /></template>
    </UiPageBar>
    <slot name="overview" />
    <UiSectionTabs :model-value="active" :items="items" @update:model-value="selectTab" />
    <RouterView />
  </div>
</template>

<style scoped>
.module-tabs-page {
  display: flex;
  flex-direction: column;
  gap: var(--yumi-ds-space-md);
}
</style>
