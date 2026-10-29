<script setup lang="ts">
withDefaults(
  defineProps<{
    title?: string
    meta?: string
  }>(),
  { title: '', meta: '' },
)
</script>

<template>
  <section class="yui-panel">
    <header v-if="title || $slots.actions" class="yui-panel__head">
      <h3 v-if="title" class="yui-panel__title">{{ title }}</h3>
      <span v-if="meta" class="yui-panel__meta">{{ meta }}</span>
      <slot name="actions" />
    </header>
    <div class="yui-panel__body">
      <slot />
    </div>
  </section>
</template>
