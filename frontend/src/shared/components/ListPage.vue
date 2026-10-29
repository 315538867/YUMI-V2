<script setup lang="ts">
import { computed } from 'vue'
import PageState from '@/shared/components/PageState.vue'
import UiPageBar from '@/shared/ui/UiPageBar.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

const props = withDefaults(
  defineProps<{
    title: string
    hint?: string
    meta?: string
    heading?: boolean
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
    heading: true,
    panelTitle: '',
    panelHint: '',
    loading: false,
    error: null,
    empty: false,
    emptyText: '暂无数据',
  },
)

defineEmits<{ retry: [] }>()

const pageMeta = computed(() => [props.hint, props.meta].filter(Boolean).join(' · '))
</script>

<template>
  <div class="list-page">
    <h1 v-if="heading" class="yumi-sr-only">{{ title }}</h1>
    <UiPageBar v-if="heading" :breadcrumb="[title]" :meta="pageMeta">
      <template #actions><slot name="actions" /></template>
    </UiPageBar>

    <WorkspacePage class="list-page__panel" padding="none">
      <div v-if="panelTitle || panelHint" class="yumi-panel__header">
        <div class="list-page__panel-copy">
          <h2 v-if="panelTitle">{{ panelTitle }}</h2>
          <span v-if="panelHint" class="yumi-hint">{{ panelHint }}</span>
        </div>
        <div v-if="$slots['panel-actions']" class="yumi-actions">
          <slot name="panel-actions" />
        </div>
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
.list-page {
  display: flex;
  flex-direction: column;
  gap: var(--yumi-ds-space-md);
}

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
