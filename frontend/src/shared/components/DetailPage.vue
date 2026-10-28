<script setup lang="ts">
import PageState from '@/shared/components/PageState.vue'
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
    <header class="yumi-page-heading">
      <div class="yumi-page-heading__copy">
        <h1 class="yumi-page-heading__title">{{ title }}</h1>
        <span v-if="$slots.meta" class="yumi-page-heading__hint"><slot name="meta" /></span>
      </div>
      <div class="yumi-actions"><slot name="actions" /></div>
    </header>

    <WorkspacePage class="detail-page__panel" padding="lg">
      <div class="detail-page__notice"><slot name="notice" /></div>
      <PageState :loading="loading" :error="error" @retry="$emit('retry')">
        <slot />
      </PageState>
    </WorkspacePage>
  </div>
</template>

<style scoped>
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
