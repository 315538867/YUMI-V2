<script setup lang="ts">
import { computed } from 'vue'
import { codeLabel } from '@/platform/display/codeCatalog'

const props = withDefaults(
  defineProps<{
    category: string
    code: string | null | undefined
    fallback?: string
  }>(),
  { fallback: '未知' },
)

const label = computed(() => codeLabel(props.category, props.code, props.fallback))
</script>

<template>
  <span>{{ label }}</span>
</template>
