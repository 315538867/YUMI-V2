<script setup lang="ts">
import type { TimelineItem } from './types'

defineProps<{
  items: TimelineItem[]
}>()
</script>

<template>
  <ol class="yui-timeline">
    <li
      v-for="(item, index) in items"
      :key="`${item.title}-${index}`"
      class="yui-timeline__item"
      :class="`yui-timeline__item--${item.tone ?? 'neutral'}`"
    >
      <span class="yui-timeline__bullet" aria-hidden="true"></span>
      <div class="yui-timeline__body">
        <div class="yui-timeline__title">{{ item.title }}</div>
        <div v-if="item.description" class="yui-timeline__description">
          {{ item.description }}
        </div>
      </div>
      <span v-if="item.time" class="yui-timeline__time">{{ item.time }}</span>
    </li>
  </ol>
</template>
