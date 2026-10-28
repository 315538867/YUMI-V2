<script setup lang="ts">
import { computed } from 'vue'
import type { MoneyWire } from '@/platform/services/money'

const props = defineProps<{
  value: MoneyWire | string | number | null | undefined
}>()

const text = computed(() => {
  if (props.value === null || props.value === undefined || props.value === '') {
    return '—'
  }
  if (typeof props.value === 'number') {
    return Number.isFinite(props.value) ? props.value.toFixed(2) : String(props.value)
  }
  if (typeof props.value === 'string') {
    return props.value
  }
  return `${props.value.amount} ${props.value.currency}`
})
</script>

<template>
  <span>{{ text }}</span>
</template>
