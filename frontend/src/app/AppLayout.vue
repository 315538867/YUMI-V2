<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import { navigationGroups } from '@/app/navigation'
import { useAuthStore } from '@/platform/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const visibleGroups = computed(() =>
  navigationGroups
    .map((group) => ({
      title: group.title,
      items: group.items.filter((item) => auth.hasPermission(item.permission)),
    }))
    .filter((group) => group.items.length > 0),
)

const activePath = computed(() => `/${route.path.split('/')[1] ?? ''}`)

async function logout(): Promise<void> {
  await auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <el-container class="app-layout">
    <el-aside width="216px" class="sidebar">
      <div class="brand">YUMI 一期</div>
      <el-menu :default-active="activePath" router class="nav">
        <template v-for="group in visibleGroups" :key="group.title">
          <div class="group-title">{{ group.title }}</div>
          <el-menu-item v-for="item in group.items" :key="item.path" :index="item.path">
            {{ item.title }}
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="page-title">{{ route.meta.title ?? '经营总览' }}</span>
        <span class="account">
          {{ auth.account?.loginName ?? '' }}
          <el-button link type="primary" @click="logout">退出</el-button>
        </span>
      </el-header>
      <el-main class="content">
        <RouterView />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.app-layout {
  min-height: 100vh;
  background: #f7f8fa;
}

.sidebar {
  background: #fff;
  border-right: 1px solid var(--el-border-color-light);
}

.brand {
  height: 56px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  font-size: 16px;
  font-weight: 600;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.nav {
  border-right: none;
}

.group-title {
  padding: 16px 20px 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.header {
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border-bottom: 1px solid var(--el-border-color-light);
}

.page-title {
  font-size: 15px;
  font-weight: 600;
}

.account {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-regular);
}

.content {
  padding: 20px;
}
</style>
