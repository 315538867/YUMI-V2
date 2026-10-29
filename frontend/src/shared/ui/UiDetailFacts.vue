<script setup lang="ts">
import type { DetailFact } from './types'

withDefaults(
  defineProps<{
    items: DetailFact[]
    columns?: 2 | 3
  }>(),
  { columns: 3 },
)
</script>

<template>
  <dl class="yui-detail-facts" :class="`yui-detail-facts--${columns}`">
    <div v-for="item in items" :key="item.label" class="yui-detail-facts__item">
      <dt class="yui-detail-facts__label">{{ item.label }}</dt>
      <dd class="yui-detail-facts__value">
        {{ item.value }}
        <small v-if="item.hint">{{ item.hint }}</small>
      </dd>
    </div>
  </dl>
</template>
