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
  <section class="yui-form-section">
    <header class="yui-form-section__head">
      <h3 class="yui-form-section__title">{{ title }}</h3>
      <span v-if="hint" class="yui-form-section__hint">{{ hint }}</span>
      <slot name="actions" />
    </header>
    <div class="yui-form-section__body">
      <slot />
    </div>
  </section>
</template>
