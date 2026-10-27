<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  value: string | null | undefined
}>()

const text = computed(() =>
  props.value ? new Date(props.value).toLocaleString('zh-CN', { hour12: false }) : '—',
)
</script>

<template>
  <span>{{ text }}</span>
</template>
