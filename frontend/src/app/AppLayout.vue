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
const pageTitle = computed(() => String(route.meta.title ?? '经营总览'))
const accountInitial = computed(() => (auth.account?.loginName ?? '?').slice(0, 1).toUpperCase())

async function logout(): Promise<void> {
  await auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <el-container class="app-layout">
    <el-aside class="sidebar">
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
        <div class="breadcrumb">
          <span>工作台 / </span>
          <strong>{{ pageTitle }}</strong>
        </div>
        <div class="account">
          <el-avatar :size="26" class="avatar">{{ accountInitial }}</el-avatar>
          <span>{{ auth.account?.loginName ?? '' }}</span>
          <el-button link type="primary" @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main class="content">
        <div class="content-inner"><RouterView /></div>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.app-layout {
  min-height: 100vh;
  background: var(--yumi-bg-page);
}

.sidebar {
  width: var(--yumi-sidebar-width);
  background: var(--yumi-color-rail);
  border-right: none;
}

.brand {
  height: var(--yumi-header-height);
  display: flex;
  align-items: center;
  padding: 0 var(--yumi-space-5);
  color: var(--yumi-color-on-dark);
  font-size: var(--yumi-font-size-lg);
  font-weight: var(--yumi-font-weight-semibold);
  border-bottom: 1px solid var(--yumi-color-rail-hover);
}

.nav {
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--yumi-color-rail-text);
  --el-menu-hover-bg-color: var(--yumi-color-rail-hover);
  --el-menu-active-color: var(--yumi-color-on-dark);
  border-right: none;
  background: transparent;
}

.nav :deep(.el-menu-item) {
  height: var(--yumi-space-10);
  margin: var(--yumi-space-1) var(--yumi-space-2);
  border-radius: var(--yumi-radius-md);
  font-size: var(--yumi-font-size-sm);
}

.nav :deep(.el-menu-item.is-active),
.nav :deep(.el-menu-item:hover) {
  background: var(--yumi-color-rail-hover);
  color: var(--yumi-color-on-dark);
}

.group-title {
  padding: var(--yumi-space-4) var(--yumi-space-5) var(--yumi-space-1);
  color: var(--yumi-color-rail-text);
  font-size: var(--yumi-font-size-xs);
}

.header {
  height: var(--yumi-header-height);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--yumi-space-6);
  background: var(--yumi-bg-surface);
  border-bottom: 1px solid var(--yumi-border-color);
}

.breadcrumb {
  display: flex;
  align-items: baseline;
  gap: var(--yumi-space-2);
  color: var(--yumi-text-secondary);
  font-size: var(--yumi-font-size-sm);
}

.breadcrumb strong {
  color: var(--yumi-text-primary);
  font-size: var(--yumi-font-size-lg);
  font-weight: var(--yumi-font-weight-semibold);
}

.account {
  display: flex;
  align-items: center;
  gap: var(--yumi-space-2);
  color: var(--yumi-text-secondary);
  font-size: var(--yumi-font-size-sm);
}

.avatar {
  background: var(--yumi-color-brand-soft);
  color: var(--yumi-color-brand-strong);
  font-weight: var(--yumi-font-weight-semibold);
}

.content {
  padding: var(--yumi-content-padding);
}

.content-inner {
  width: 100%;
  max-width: var(--yumi-content-max-width);
  margin: 0 auto;
}
</style>
