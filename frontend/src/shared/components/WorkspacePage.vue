<script setup lang="ts">
withDefaults(defineProps<{ padding?: 'none' | 'sm' | 'md' | 'lg' }>(), { padding: 'md' })
</script>

<template>
  <section class="workspace-page" :class="`workspace-page--${padding}`">
    <slot />
  </section>
</template>

<style scoped>
.workspace-page {
  background: var(--yumi-bg-surface);
  border: 1px solid var(--yumi-border-color);
  border-radius: var(--yumi-radius-lg);
}

.workspace-page--none {
  padding: 0;
}

.workspace-page--sm {
  padding: var(--yumi-space-3);
}

.workspace-page--md {
  padding: var(--yumi-card-padding);
}

.workspace-page--lg {
  padding: var(--yumi-space-5);
}
</style>
