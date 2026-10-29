<script setup lang="ts">
withDefaults(
  defineProps<{
    breadcrumb: string[]
    range?: string
    meta?: string
  }>(),
  { range: '', meta: '' },
)
</script>

<template>
  <div class="yui-pagebar">
    <div class="yui-pagebar__context">
      <span class="yui-pagebar__crumb">
        <template v-for="(item, index) in breadcrumb" :key="item">
          <i v-if="index > 0">/</i>
          <b v-if="index === breadcrumb.length - 1">{{ item }}</b>
          <template v-else>{{ item }}</template>
        </template>
      </span>
      <span v-if="range" class="yui-pagebar__sep"></span>
      <span v-if="range" class="yui-pagebar__range">{{ range }}</span>
      <span v-if="meta" class="yui-pagebar__sep"></span>
      <span v-if="meta" class="yui-pagebar__meta">{{ meta }}</span>
    </div>
    <div v-if="$slots.actions" class="yui-pagebar__actions">
      <slot name="actions" />
    </div>
  </div>
</template>
