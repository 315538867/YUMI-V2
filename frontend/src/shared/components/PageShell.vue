<script setup lang="ts">
withDefaults(
  defineProps<{
    title: string
    hint?: string
  }>(),
  { hint: '' },
)
</script>

<template>
  <div class="page-shell">
    <header class="yumi-page-heading">
      <div class="yumi-page-heading__copy">
        <h1 class="yumi-page-heading__title">{{ title }}</h1>
        <span v-if="hint" class="yumi-page-heading__hint">{{ hint }}</span>
        <slot name="meta" />
      </div>
      <div class="yumi-actions"><slot name="actions" /></div>
    </header>
    <slot />
  </div>
</template>
