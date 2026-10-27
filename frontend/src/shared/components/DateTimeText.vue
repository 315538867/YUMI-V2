<script setup lang="ts">
import { computed } from 'vue'
import { formatDateTime } from '@/platform/display/dateTime'

const props = defineProps<{
  value: string | null | undefined
}>()

const text = computed(() => formatDateTime(props.value))
</script>

<template>
  <span>{{ text }}</span>
</template>
