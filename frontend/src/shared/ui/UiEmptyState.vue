<script setup lang="ts">
withDefaults(
  defineProps<{
    title?: string
    hint?: string
  }>(),
  { title: '暂无数据', hint: '' },
)
</script>

<template>
  <div class="yui-empty">
    <span class="yui-empty__plus">＋</span>
    <span class="yui-empty__title">{{ title }}</span>
    <span v-if="hint" class="yui-empty__hint">{{ hint }}</span>
  </div>
</template>
