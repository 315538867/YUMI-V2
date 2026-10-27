<script setup lang="ts">
import PageState from '@/shared/components/PageState.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

withDefaults(
  defineProps<{
    title: string
    meta?: string
    loading?: boolean
    error?: string | null
    empty?: boolean
    emptyText?: string
  }>(),
  { meta: '', loading: false, error: null, empty: false, emptyText: '暂无数据' },
)

defineEmits<{ retry: [] }>()
</script>

<template>
  <WorkspacePage class="list-page">
    <header class="list-page__head">
      <h1>{{ title }}</h1>
      <span v-if="meta" class="list-page__meta">{{ meta }}</span>
    </header>
    <div v-if="$slots.filters || $slots.actions" class="list-page__toolbar">
      <slot name="filters" />
      <slot name="actions" />
    </div>
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
  </WorkspacePage>
</template>

<style scoped>
.list-page__head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
}

.list-page__head h1 {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}

.list-page__meta {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.list-page__toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.list-page__notice:not(:empty),
.list-page__footer:not(:empty) {
  margin-bottom: 12px;
}

.list-page__footer:not(:empty) {
  margin-top: 12px;
  margin-bottom: 0;
}
</style>
