<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const active = computed(() => route.path)

const tabs = [
  { path: '/workforce/employees', label: '员工与资格' },
  { path: '/workforce/teams', label: '小组' },
  { path: '/workforce/standards', label: '人员标准' },
  { path: '/workforce/activity-references', label: '活动引用' },
]
</script>

<template>
  <el-tabs
    :model-value="active"
    class="workforce-tabs"
    @tab-change="(path: string | number) => router.push(String(path))"
  >
    <el-tab-pane v-for="tab in tabs" :key="tab.path" :label="tab.label" :name="tab.path" />
  </el-tabs>
  <RouterView />
</template>

<style scoped>
.workforce-tabs {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 0 16px;
  margin-bottom: 12px;
}
</style>
