<script setup lang="ts">
import PageState from '@/shared/components/PageState.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

withDefaults(
  defineProps<{
    title: string
    hint?: string
    meta?: string
    panelTitle?: string
    panelHint?: string
    loading?: boolean
    error?: string | null
    empty?: boolean
    emptyText?: string
  }>(),
  {
    hint: '',
    meta: '',
    panelTitle: '',
    panelHint: '',
    loading: false,
    error: null,
    empty: false,
    emptyText: '暂无数据',
  },
)

defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="list-page">
    <header class="yumi-page-heading">
      <div class="yumi-page-heading__copy">
        <h1 class="yumi-page-heading__title">{{ title }}</h1>
        <span v-if="hint" class="yumi-page-heading__hint">{{ hint }}</span>
        <span v-if="meta" class="yumi-page-heading__hint">{{ meta }}</span>
      </div>
      <div class="yumi-actions"><slot name="actions" /></div>
    </header>

    <WorkspacePage class="list-page__panel" padding="none">
      <div v-if="panelTitle || panelHint" class="yumi-panel__header">
        <div class="list-page__panel-copy">
          <h2 v-if="panelTitle">{{ panelTitle }}</h2>
          <span v-if="panelHint" class="yumi-hint">{{ panelHint }}</span>
        </div>
        <slot name="panel-actions" />
      </div>
      <div class="list-page__body">
        <div v-if="$slots.filters" class="yumi-toolbar"><slot name="filters" /></div>
        <div class="list-page__notice"><slot name="notice" /></div>
        <PageState
          :loading="loading"
          :error="error"
          :empty="empty"
          :empty-text="emptyText"
          @retry="$emit('retry')"
        >
          <slot />
          <slot name="pager" />
        </PageState>
        <div class="list-page__footer"><slot name="footer" /></div>
      </div>
    </WorkspacePage>
  </div>
</template>

<style scoped>
.list-page__panel-copy {
  display: flex;
  align-items: baseline;
  gap: var(--yumi-space-3);
  min-width: 0;
}

.list-page__body {
  padding: var(--yumi-space-5);
}

.list-page__notice:not(:empty) {
  margin-bottom: var(--yumi-space-3);
}

.list-page__footer:not(:empty) {
  margin-top: var(--yumi-space-3);
}
</style>
