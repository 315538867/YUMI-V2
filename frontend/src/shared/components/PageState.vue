<script setup lang="ts">
withDefaults(
  defineProps<{
    loading?: boolean
    error?: string | null
    empty?: boolean
    emptyText?: string
  }>(),
  { loading: false, error: null, empty: false, emptyText: '暂无数据' },
)

defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="page-state">
    <div v-if="error" class="page-state__error">
      <el-alert type="error" :title="error" :closable="false" show-icon />
      <el-button data-test="retry" @click="$emit('retry')">重新加载</el-button>
    </div>
    <div v-loading="loading" class="page-state__body">
      <el-empty v-if="empty && !loading" :description="emptyText" />
      <slot v-else />
    </div>
  </div>
</template>

<style scoped>
.page-state__error {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}

.page-state__error :deep(.el-alert) {
  flex: 1;
}

.page-state__body {
  min-height: 80px;
}
</style>
