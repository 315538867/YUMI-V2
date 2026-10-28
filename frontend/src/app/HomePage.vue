<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { navigationItems } from '@/app/navigation'
import { useAuthStore } from '@/platform/stores/auth'
import PageShell from '@/shared/components/PageShell.vue'
import WorkspacePage from '@/shared/components/WorkspacePage.vue'

const auth = useAuthStore()
const router = useRouter()

const entries = computed(() =>
  navigationItems.filter((item) => auth.hasPermission(item.permission)),
)
</script>

<template>
  <PageShell title="经营总览" hint="选择模块进入；所有数字以服务端接口为准">
    <WorkspacePage class="dashboard">
      <div class="grid">
        <button
          v-for="entry in entries"
          :key="entry.path"
          type="button"
          class="entry yumi-panel"
          @click="router.push(entry.path)"
        >
          {{ entry.title }}
        </button>
      </div>
    </WorkspacePage>
  </PageShell>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: var(--yumi-space-3);
}

.entry {
  padding: var(--yumi-space-5);
  text-align: left;
  cursor: pointer;
  font-size: var(--yumi-font-size-md);
}

.entry:hover {
  border-color: var(--yumi-color-brand);
  color: var(--yumi-color-brand);
}
</style>
