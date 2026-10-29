<script setup lang="ts">
withDefaults(
  defineProps<{
    label: string
    count?: number | null
    active?: boolean
    tone?: 'default' | 'todo'
  }>(),
  { count: null, active: false, tone: 'default' },
)

defineEmits<{ click: [] }>()
</script>

<template>
  <button
    type="button"
    class="yui-filter"
    :class="{ 'yui-filter--active': active, 'yui-filter--todo': tone === 'todo' }"
    @click="$emit('click')"
  >
    {{ label }}
    <span v-if="count !== null" class="yui-filter__count">{{ count }}</span>
  </button>
</template>
