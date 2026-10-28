<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'

withDefaults(
  defineProps<{
    title: string
    hint?: string
    tabs: ReadonlyArray<{ path: string; label: string }>
  }>(),
  { hint: '' },
)

const route = useRoute()
const router = useRouter()
const active = computed(() => route.path)
</script>

<template>
  <header class="yumi-page-heading">
    <div class="yumi-page-heading__copy">
      <h1 class="yumi-page-heading__title">{{ title }}</h1>
      <span v-if="hint" class="yumi-page-heading__hint">{{ hint }}</span>
    </div>
    <div v-if="$slots.actions" class="yumi-actions"><slot name="actions" /></div>
  </header>
  <slot name="overview" />
  <el-tabs
    :model-value="active"
    class="yumi-module-tabs"
    @tab-change="(path: string | number) => router.push(String(path))"
  >
    <el-tab-pane v-for="tab in tabs" :key="tab.path" :label="tab.label" :name="tab.path" />
  </el-tabs>
  <RouterView />
</template>
