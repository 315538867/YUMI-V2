<script setup lang="ts">
import type { SectionTab } from './types'

withDefaults(
  defineProps<{
    modelValue: string
    items: SectionTab[]
  }>(),
  {},
)

defineEmits<{ 'update:modelValue': [key: string] }>()
</script>

<template>
  <nav class="yui-section-tabs" role="tablist" aria-label="分区导航">
    <button
      v-for="item in items"
      :key="item.key"
      type="button"
      role="tab"
      class="yui-section-tabs__item"
      :class="{ 'yui-section-tabs__item--active': item.key === modelValue }"
      :aria-selected="item.key === modelValue"
      @click="$emit('update:modelValue', item.key)"
    >
      {{ item.label }}
      <span v-if="item.count !== null && item.count !== undefined" class="yui-section-tabs__count">
        {{ item.count }}
      </span>
    </button>
  </nav>
</template>
