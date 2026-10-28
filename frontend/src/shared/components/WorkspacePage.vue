<script setup lang="ts">
withDefaults(
  defineProps<{
    padding?: 'none' | 'sm' | 'md' | 'lg'
    title?: string
    hint?: string
  }>(),
  { padding: 'md', title: '', hint: '' },
)
</script>

<template>
  <section
    class="workspace-page"
    :class="[
      `workspace-page--${padding}`,
      { 'workspace-page--headed': title || hint || $slots.actions },
    ]"
  >
    <header v-if="title || hint || $slots.actions" class="yumi-panel__header">
      <div class="workspace-page__copy">
        <h2 v-if="title">{{ title }}</h2>
        <span v-if="hint" class="yumi-hint">{{ hint }}</span>
        <slot name="meta" />
      </div>
      <div v-if="$slots.actions" class="yumi-actions"><slot name="actions" /></div>
    </header>
    <div class="workspace-page__body"><slot /></div>
  </section>
</template>

<style scoped>
.workspace-page {
  background: var(--yumi-bg-surface);
  border: 1px solid var(--yumi-border-color);
  border-radius: var(--yumi-radius-lg);
  padding: var(--workspace-page-padding);
}

.workspace-page--none {
  --workspace-page-padding: 0;
}

.workspace-page--sm {
  --workspace-page-padding: var(--yumi-space-3);
}

.workspace-page--md {
  --workspace-page-padding: var(--yumi-card-padding);
}

.workspace-page--lg {
  --workspace-page-padding: var(--yumi-space-5);
}

.workspace-page--headed {
  padding: 0;
}

.workspace-page--headed > .workspace-page__body {
  padding: var(--workspace-page-padding);
}

.workspace-page__copy {
  display: flex;
  align-items: baseline;
  gap: var(--yumi-space-3);
  min-width: 0;
  flex-wrap: wrap;
}
</style>
