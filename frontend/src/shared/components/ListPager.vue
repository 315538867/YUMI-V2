<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    page: number
    size: number
    count: number
    sizes?: number[]
  }>(),
  { sizes: () => [20, 50, 100] },
)

const emit = defineEmits<{
  'update:page': [page: number]
  'update:size': [size: number]
}>()

const hasNext = computed(() => props.count >= props.size)
const sizeOptions = computed(() => props.sizes)
</script>

<template>
  <div class="list-pager">
    <span class="summary">第 {{ page }} 页 · 本页 {{ count }} 条</span>
    <el-button data-test="prev" :disabled="page <= 1" @click="emit('update:page', page - 1)"
      >上一页</el-button
    >
    <el-button data-test="next" :disabled="!hasNext" @click="emit('update:page', page + 1)"
      >下一页</el-button
    >
    <el-select
      :model-value="size"
      class="size"
      aria-label="每页条数"
      @update:model-value="(value: number) => emit('update:size', Number(value))"
    >
      <el-option v-for="item in sizeOptions" :key="item" :label="`每页 ${item} 条`" :value="item" />
    </el-select>
  </div>
</template>

<style scoped>
.list-pager {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}

.summary {
  margin-right: auto;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.size {
  width: 120px;
}
</style>
