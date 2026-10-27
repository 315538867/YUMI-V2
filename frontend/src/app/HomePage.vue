<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { navigationGroups } from '@/app/navigation'
import { useAuthStore } from '@/platform/stores/auth'

const auth = useAuthStore()
const router = useRouter()

const entries = computed(() =>
  navigationGroups
    .flatMap((group) => group.items)
    .filter((item) => auth.hasPermission(item.permission)),
)
</script>

<template>
  <section class="dashboard">
    <h1>经营总览</h1>
    <p class="hint">选择模块进入；所有数字以服务端接口为准。</p>
    <div class="grid">
      <button
        v-for="entry in entries"
        :key="entry.path"
        type="button"
        class="entry"
        @click="router.push(entry.path)"
      >
        {{ entry.title }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.dashboard {
  background: var(--yumi-surface);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 24px;
}

h1 {
  margin: 0 0 4px;
  font-size: 20px;
}

.hint {
  margin: 0 0 20px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
}

.entry {
  padding: 18px;
  text-align: left;
  background: var(--yumi-surface);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
}

.entry:hover {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
</style>
