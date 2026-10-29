<script setup lang="ts">
import UiPageBar from '@/shared/ui/UiPageBar.vue'

withDefaults(
  defineProps<{
    title: string
    hint?: string
  }>(),
  { hint: '' },
)
</script>

<template>
  <div class="page-shell">
    <h1 class="yumi-sr-only">{{ title }}</h1>
    <UiPageBar :breadcrumb="[title]" :meta="hint">
      <template #actions><slot name="actions" /></template>
    </UiPageBar>
    <div v-if="$slots.meta" class="page-shell__meta"><slot name="meta" /></div>
    <slot />
  </div>
</template>

<style scoped>
.page-shell {
  display: flex;
  flex-direction: column;
  gap: var(--yumi-ds-space-xl);
}

.page-shell__meta {
  display: flex;
  align-items: center;
  gap: var(--yumi-ds-space-sm);
}
</style>
