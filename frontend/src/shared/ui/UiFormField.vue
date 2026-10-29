<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    required?: boolean
    hint?: string
    error?: string
  }>(),
  { required: false, hint: '', error: '' },
)
</script>

<template>
  <label class="yui-field">
    <span class="yui-field__label">
      {{ label }}
      <span v-if="required" class="yui-field__required">*</span>
    </span>
    <slot />
    <span v-if="error" class="yui-field__error">{{ error }}</span>
    <span v-else-if="hint" class="yui-field__hint">{{ hint }}</span>
  </label>
</template>
