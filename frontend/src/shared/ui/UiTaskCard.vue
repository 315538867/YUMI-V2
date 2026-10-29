<script setup lang="ts">
import { computed } from 'vue'
import { employeeColorIndex } from './employeeColor'
import type { TaskStatus } from './types'
import UiEmployeeBadge from './UiEmployeeBadge.vue'
import UiStatusPill from './UiStatusPill.vue'

const props = withDefaults(
  defineProps<{
    taskNo: string
    product: string
    variant?: string
    employeeName: string
    employeeId?: string
    colorIndex?: number
    typeLabel: string
    quantity: number | string
    unit?: string
    status: TaskStatus
    statusLabel?: string
  }>(),
  {
    variant: '',
    employeeId: '',
    colorIndex: -1,
    unit: '件',
    statusLabel: '',
  },
)

defineEmits<{ click: [] }>()

const empIndex = computed(() => employeeColorIndex(props.employeeId, props.colorIndex))
</script>

<template>
  <button type="button" class="yui-task" :class="`yui-emp--${empIndex}`" @click="$emit('click')">
    <div class="yui-task__top">
      <UiEmployeeBadge :name="employeeName" :employee-id="employeeId" :color-index="empIndex" />
      <span class="yui-task__no">{{ taskNo }}</span>
    </div>
    <div class="yui-task__title">
      {{ product }}
      <span v-if="variant">{{ variant }}</span>
    </div>
    <div class="yui-task__meta">
      <span class="yui-task__type">{{ typeLabel }}</span>
      <span class="yui-task__qty"
        >{{ quantity }}<small>{{ unit }}</small></span
      >
      <UiStatusPill :status="status" :label="statusLabel" />
    </div>
  </button>
</template>
