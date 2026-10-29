<script setup lang="ts">
import type { SelectOption } from './types'

withDefaults(
  defineProps<{
    modelValue: string
    options: SelectOption[]
    placeholder?: string
    ariaLabel?: string
  }>(),
  { placeholder: '请选择', ariaLabel: '' },
)

defineEmits<{ 'update:modelValue': [value: string] }>()
</script>

<template>
  <el-select
    :model-value="modelValue"
    class="yui-el-select"
    :placeholder="placeholder"
    :aria-label="ariaLabel"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <el-option
      v-for="option in options"
      :key="option.value"
      :label="option.label"
      :value="option.value"
    />
  </el-select>
</template>
