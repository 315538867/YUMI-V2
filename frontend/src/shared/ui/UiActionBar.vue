<script setup lang="ts">
withDefaults(
  defineProps<{
    sticky?: boolean
  }>(),
  { sticky: true },
)
</script>

<template>
  <div class="yui-actionbar" :class="{ 'yui-actionbar--sticky': sticky }">
    <div class="yui-actionbar__content">
      <slot />
    </div>
    <div v-if="$slots.actions" class="yui-actionbar__actions">
      <slot name="actions" />
    </div>
  </div>
</template>
