<script setup lang="ts">
import { computed } from 'vue'
import { codeLabel, codeTagType } from '@/platform/display/codeCatalog'

const props = withDefaults(
  defineProps<{
    category: string
    code: string | null | undefined
    fallback?: string
  }>(),
  { fallback: '未知' },
)

const label = computed(() => codeLabel(props.category, props.code, props.fallback))
const tagType = computed(() => codeTagType(props.category, props.code) || undefined)
</script>

<template>
  <el-tag :type="tagType" size="small" effect="plain">{{ label }}</el-tag>
</template>
