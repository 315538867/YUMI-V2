<script setup lang="ts">
import PageState from '@/shared/components/PageState.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

withDefaults(
  defineProps<{
    title: string
    previewTitle?: string
    previewHint?: string
    loading?: boolean
    error?: string | null
  }>(),
  { previewTitle: '资料预览', previewHint: '', loading: false, error: null },
)

defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="editor-page">
    <header class="yumi-page-heading">
      <div class="yumi-page-heading__copy">
        <h1 class="yumi-page-heading__title">{{ title }}</h1>
      </div>
      <div class="yumi-actions"><slot name="actions" /></div>
    </header>

    <WorkspacePage class="editor-page__panel" padding="lg">
      <div class="editor-page__notice"><slot name="notice" /></div>
      <PageState :loading="loading" :error="error" @retry="$emit('retry')">
        <div class="editor-page__grid">
          <div class="editor-page__form"><slot /></div>
          <aside class="editor-page__preview">
            <h2 class="yumi-panel__title">{{ previewTitle }}</h2>
            <slot name="preview" />
            <p v-if="previewHint" class="yumi-hint editor-page__hint">{{ previewHint }}</p>
          </aside>
        </div>
      </PageState>
    </WorkspacePage>
  </div>
</template>

<style scoped>
.editor-page__notice:not(:empty) {
  margin-bottom: var(--yumi-space-3);
}

.editor-page__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: var(--yumi-space-4);
  align-items: start;
}

.editor-page__form {
  max-width: 720px;
}

.editor-page__form :deep(h2) {
  margin: var(--yumi-section-gap) 0 var(--yumi-space-3);
  font-size: var(--yumi-font-size-subtitle);
  font-weight: var(--yumi-font-weight-semibold);
  color: var(--yumi-text-primary);
}

.editor-page__form :deep(h2:first-child) {
  margin-top: 0;
}

.editor-page__form :deep(.yumi-alert) {
  margin-bottom: var(--yumi-space-3);
}

.editor-page__preview {
  position: sticky;
  top: var(--yumi-space-3);
  border: 1px solid var(--yumi-border-color);
  border-radius: var(--yumi-radius-md);
  padding: var(--yumi-space-3) var(--yumi-space-4);
  background: var(--yumi-bg-muted);
}

.editor-page__preview :deep(.yumi-hint) {
  margin-top: var(--yumi-space-3);
}

.editor-page__hint {
  margin: var(--yumi-space-3) 0 0;
}

@media (max-width: 1100px) {
  .editor-page__grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .editor-page__preview {
    position: static;
  }
}
</style>
