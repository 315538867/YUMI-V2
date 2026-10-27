<script setup lang="ts">
import { computed } from 'vue'
import type { MoneyWire } from '@/platform/services/money'

const props = defineProps<{
  value: MoneyWire | string | null | undefined
}>()

const text = computed(() => {
  if (props.value === null || props.value === undefined || props.value === '') {
    return '—'
  }
  return typeof props.value === 'string'
    ? props.value
    : `${props.value.amount} ${props.value.currency}`
})
</script>

<template>
  <span>{{ text }}</span>
</template>
