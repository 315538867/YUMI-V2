<script setup lang="ts">
import UiButton from './UiButton.vue'

withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    width?: string
    confirmText?: string
    cancelText?: string
    loading?: boolean
  }>(),
  {
    width: '560px',
    confirmText: '确认',
    cancelText: '取消',
    loading: false,
  },
)

const emit = defineEmits<{
  'update:modelValue': [value: boolean]
  confirm: []
  cancel: []
}>()

function close(): void {
  emit('update:modelValue', false)
  emit('cancel')
}
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    :width="width"
    class="yui-dialog"
    destroy-on-close
    :close-on-click-modal="false"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <slot />
    <template #footer>
      <div class="yui-dialog__footer">
        <UiButton @click="close">{{ cancelText }}</UiButton>
        <UiButton variant="primary" :disabled="loading" @click="$emit('confirm')">
          {{ loading ? '处理中…' : confirmText }}
        </UiButton>
      </div>
    </template>
  </el-dialog>
</template>
