<script setup lang="ts">
import PageState from '@/shared/components/PageState.vue'
import UiPageBar from '@/shared/ui/UiPageBar.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

withDefaults(
  defineProps<{
    title: string
    loading?: boolean
    error?: string | null
  }>(),
  { loading: false, error: null },
)

defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="detail-page">
    <h1 class="yumi-sr-only">{{ title }}</h1>
    <UiPageBar :breadcrumb="[title]">
      <template #actions><slot name="actions" /></template>
    </UiPageBar>
    <div v-if="$slots.meta" class="detail-page__meta"><slot name="meta" /></div>

    <WorkspacePage class="detail-page__panel" padding="lg">
      <div class="detail-page__notice"><slot name="notice" /></div>
      <PageState :loading="loading" :error="error" @retry="$emit('retry')">
        <slot />
      </PageState>
    </WorkspacePage>
  </div>
</template>

<style scoped>
.detail-page {
  display: flex;
  flex-direction: column;
  gap: var(--yumi-ds-space-md);
}

.detail-page__meta {
  display: flex;
  align-items: center;
  gap: var(--yumi-ds-space-sm);
}

.detail-page__notice:not(:empty) {
  margin-bottom: var(--yumi-space-3);
}

.detail-page :deep(h2) {
  margin: var(--yumi-section-gap) 0 var(--yumi-space-3);
  font-size: var(--yumi-font-size-subtitle);
  font-weight: var(--yumi-font-weight-semibold);
  color: var(--yumi-text-primary);
}
</style>
