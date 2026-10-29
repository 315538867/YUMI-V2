<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  value: number
  max: number
  label?: string
}>()

const percent = computed(() => {
  if (props.max <= 0) {
    return 0
  }
  return Math.min(100, Math.round((props.value / props.max) * 100))
})
</script>

<template>
  <div class="yui-capacity">
    <div class="yui-capacity__bar">
      <span class="yui-capacity__fill" :style="{ width: `${percent}%` }"></span>
    </div>
    <span class="yui-capacity__label">{{ label ?? `${value} / ${max}` }}</span>
  </div>
</template>
