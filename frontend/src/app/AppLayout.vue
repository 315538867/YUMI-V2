<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { navigationItems } from '@/app/navigation'
import { useAuthStore } from '@/platform/stores/auth'
import UiTopNav from '@/shared/ui/UiTopNav.vue'
import type { TopNavItem } from '@/shared/ui/types'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const visibleItems = computed(() =>
  navigationItems.filter((item) => auth.hasPermission(item.permission)),
)

const navItems = computed<TopNavItem[]>(() =>
  visibleItems.value.map((item) => ({ key: item.path, label: item.title, path: item.path })),
)

const activeKey = computed(() => {
  const path = route.path
  const matches = navItems.value
    .filter((item) => path === item.path || path.startsWith(`${item.path}/`))
    .sort((left, right) => (right.path?.length ?? 0) - (left.path?.length ?? 0))
  return matches[0]?.key ?? ''
})

const accountInitial = computed(() => (auth.account?.loginName ?? '?').slice(0, 1).toUpperCase())

function go(path: string): void {
  void router.push(path)
}

function goHome(): void {
  void router.push('/')
}

async function logout(): Promise<void> {
  await auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <div class="app-shell">
    <UiTopNav
      :items="navItems"
      :active="activeKey"
      :account-initial="accountInitial"
      :show-search="false"
      :show-notifications="false"
      show-logout
      @select="go"
      @home="goHome"
      @logout="logout"
    />
    <main class="content">
      <div class="content-inner"><RouterView /></div>
    </main>
  </div>
</template>

<style scoped>
.app-shell {
  min-height: 100vh;
  background: var(--yumi-ds-canvas);
  color: var(--yumi-ds-text);
  font-family: var(--yumi-ds-font-ui);
}

.content {
  min-height: calc(100vh - var(--yumi-ds-topnav-height));
}

.content-inner {
  width: 100%;
  max-width: var(--yumi-ds-content-width);
  margin: 0 auto;
  padding: var(--yumi-ds-space-xl) var(--yumi-ds-space-4xl) var(--yumi-ds-space-5xl);
}
</style>
