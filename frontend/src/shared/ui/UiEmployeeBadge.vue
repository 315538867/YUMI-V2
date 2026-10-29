<script setup lang="ts">
import { computed } from 'vue'
import { employeeColorIndex } from './employeeColor'

const props = withDefaults(
  defineProps<{
    name: string
    employeeId?: string
    colorIndex?: number
    showName?: boolean
  }>(),
  { employeeId: '', colorIndex: -1, showName: true },
)

const index = computed(() => employeeColorIndex(props.employeeId, props.colorIndex))
const initial = computed(() => Array.from(props.name)[0] ?? '?')
</script>

<template>
  <span class="yui-employee" :class="`yui-emp--${index}`">
    <span class="yui-employee__avatar">{{ initial }}</span>
    <span v-if="showName" class="yui-employee__name">{{ name }}</span>
  </span>
</template>
