<script setup lang="ts">
withDefaults(defineProps<{ padding?: number }>(), { padding: 16 })
</script>

<template>
  <section class="workspace-page" :style="{ padding: `${padding}px` }">
    <slot />
  </section>
</template>

<style scoped>
.workspace-page {
  background: var(--yumi-surface);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}
</style>
