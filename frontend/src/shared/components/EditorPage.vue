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
  <WorkspacePage class="editor-page">
    <header class="editor-page__head">
      <h1>{{ title }}</h1>
      <div class="editor-page__actions"><slot name="actions" /></div>
    </header>
    <div class="editor-page__notice"><slot name="notice" /></div>
    <PageState :loading="loading" :error="error" @retry="$emit('retry')">
      <div class="editor-page__grid">
        <div class="editor-page__form"><slot /></div>
        <aside class="editor-page__preview">
          <h2>{{ previewTitle }}</h2>
          <slot name="preview" />
          <p v-if="previewHint" class="editor-page__hint">{{ previewHint }}</p>
        </aside>
      </div>
    </PageState>
  </WorkspacePage>
</template>

<style scoped>
.editor-page__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  gap: 12px;
}

.editor-page__head h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.editor-page__actions {
  display: flex;
  gap: 8px;
}

.editor-page__notice:not(:empty) {
  margin-bottom: 12px;
}

.editor-page__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 16px;
  align-items: start;
}

.editor-page__form {
  max-width: 720px;
}

.editor-page__form :deep(h2) {
  margin: 20px 0 10px;
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.editor-page__form :deep(h2:first-child) {
  margin-top: 0;
}

.editor-page__form :deep(.alert) {
  margin-bottom: 12px;
}

.editor-page__preview {
  position: sticky;
  top: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 12px 16px;
  background: var(--el-fill-color-lighter);
}

.editor-page__preview h2 {
  margin: 0 0 10px;
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.editor-page__preview :deep(dl) {
  display: grid;
  grid-template-columns: 96px 1fr;
  gap: 8px 12px;
  margin: 0;
}

.editor-page__preview :deep(dt) {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.editor-page__preview :deep(dd) {
  margin: 0;
  font-size: 13px;
  word-break: break-all;
}

.editor-page__hint {
  margin: 12px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.editor-page__preview :deep(.hint) {
  margin: 12px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
