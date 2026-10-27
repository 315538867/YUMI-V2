<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const active = computed(() => route.path)
const tabs = [
  { path: '/settings/accounts', label: '账号与角色' },
  { path: '/settings/glue', label: '胶水配置' },
  { path: '/settings/audit', label: '审计检索' },
  { path: '/settings/tasks', label: '提交后任务' },
]
</script>

<template>
  <el-tabs
    :model-value="active"
    class="settings-tabs"
    @tab-change="(path: string | number) => router.push(String(path))"
  >
    <el-tab-pane v-for="tab in tabs" :key="tab.path" :label="tab.label" :name="tab.path" />
  </el-tabs>
  <RouterView />
</template>

<style scoped>
.settings-tabs {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 0 16px;
  margin-bottom: 12px;
}
</style>
