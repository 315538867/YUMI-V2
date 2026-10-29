<script setup lang="ts">
import type { SourceNode } from './types'

defineProps<{
  nodes: SourceNode[]
}>()
</script>

<template>
  <ol class="yui-source-chain">
    <li v-for="node in nodes" :key="`${node.label}-${node.value}`" class="yui-source-chain__node">
      <span class="yui-source-chain__label">{{ node.label }}</span>
      <b class="yui-source-chain__value">{{ node.value }}</b>
      <small v-if="node.hint" class="yui-source-chain__hint">{{ node.hint }}</small>
    </li>
  </ol>
</template>
