<script setup lang="ts">
import { computed } from 'vue'

type StatusTone = 'pending' | 'done' | 'arranged' | 'extra' | 'repair'

const props = withDefaults(
  defineProps<{
    status: StatusTone
    label?: string
  }>(),
  { label: '' },
)

const text = computed(() => {
  if (props.label) {
    return props.label
  }
  return {
    pending: '待核验',
    done: '已核验',
    arranged: '已排',
    extra: '超额',
    repair: '返工',
  }[props.status]
})
</script>

<template>
  <span class="yui-status" :class="status !== 'arranged' ? `yui-status--${status}` : ''">
    {{ text }}
  </span>
</template>
