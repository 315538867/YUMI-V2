<script setup lang="ts">
withDefaults(
  defineProps<{
    modelValue: string
    placeholder?: string
    disabled?: boolean
  }>(),
  { placeholder: '', disabled: false },
)

defineEmits<{ 'update:modelValue': [value: string] }>()
</script>

<template>
  <el-input
    :model-value="modelValue"
    :placeholder="placeholder"
    :disabled="disabled"
    class="yui-el-input"
    @update:model-value="$emit('update:modelValue', $event)"
  />
</template>
