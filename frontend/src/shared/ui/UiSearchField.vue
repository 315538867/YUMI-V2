<script setup lang="ts">
import { Search } from '@element-plus/icons-vue'

withDefaults(
  defineProps<{
    modelValue: string
    placeholder?: string
    ariaLabel?: string
  }>(),
  { placeholder: '搜索', ariaLabel: '搜索' },
)

const emit = defineEmits<{
  'update:modelValue': [value: string]
  search: []
}>()

function onInput(event: Event): void {
  emit('update:modelValue', (event.target as HTMLInputElement).value)
}
</script>

<template>
  <div class="yui-search">
    <el-icon :size="14"><Search /></el-icon>
    <input
      :value="modelValue"
      :placeholder="placeholder"
      :aria-label="ariaLabel"
      @input="onInput"
      @keyup.enter="$emit('search')"
    />
  </div>
</template>
