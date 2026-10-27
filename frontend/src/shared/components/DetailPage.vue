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
  <WorkspacePage class="detail-page">
    <header class="detail-page__head">
      <div class="detail-page__title">
        <h1>{{ title }}</h1>
        <span v-if="$slots.meta" class="detail-page__meta"><slot name="meta" /></span>
      </div>
      <div class="detail-page__actions"><slot name="actions" /></div>
    </header>
    <div class="detail-page__notice"><slot name="notice" /></div>
    <PageState :loading="loading" :error="error" @retry="$emit('retry')">
      <slot />
    </PageState>
  </WorkspacePage>
</template>

<style scoped>
.detail-page__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.detail-page__title h1 {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 600;
}

.detail-page__meta {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.detail-page__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

.detail-page__notice:not(:empty) {
  margin-bottom: 12px;
}

.detail-page :deep(h2) {
  margin: 20px 0 10px;
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.detail-page :deep(.detail-page__section-head) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 20px 0 10px;
}

.detail-page :deep(.detail-page__section-head h2) {
  margin: 0;
}
</style>
