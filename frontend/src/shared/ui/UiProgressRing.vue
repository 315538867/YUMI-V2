<script setup lang="ts">
withDefaults(
  defineProps<{
    percent: number
    label?: string
    caption?: string
  }>(),
  { label: '', caption: '' },
)
</script>

<template>
  <div class="yui-rhythm">
    <div class="yui-ring" :style="{ '--value': String(percent) }">
      <span class="yui-ring__value">{{ percent }}%</span>
    </div>
    <div v-if="label || caption" class="yui-rhythm__text">
      <b v-if="label">{{ label }}</b>
      <small v-if="caption">{{ caption }}</small>
    </div>
  </div>
</template>
