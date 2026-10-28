<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { navigationItems } from '@/app/navigation'
import { useAuthStore } from '@/platform/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const visibleItems = computed(() =>
  navigationItems.filter((item) => auth.hasPermission(item.permission)),
)

const pageTitle = computed(() => String(route.meta.title ?? '经营总览'))
const accountInitial = computed(() => (auth.account?.loginName ?? '?').slice(0, 1).toUpperCase())

async function logout(): Promise<void> {
  await auth.logout()
  await router.replace({ name: 'login' })
}
</script>

<template>
  <el-container class="app-layout">
    <el-aside class="rail">
      <RouterLink to="/" class="brand" active-class="brand--active" aria-label="YU 返回经营总览">
        YU
      </RouterLink>
      <nav class="rail-nav" aria-label="模块导航">
        <el-tooltip
          v-for="item in visibleItems"
          :key="item.path"
          :content="item.title"
          placement="right"
        >
          <RouterLink
            :to="item.path"
            class="rail-btn"
            active-class="rail-btn--active"
            :aria-label="item.title"
          >
            <el-icon :size="19"><component :is="item.icon" /></el-icon>
          </RouterLink>
        </el-tooltip>
      </nav>
      <div class="rail-footer">一期</div>
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

.rail {
  position: sticky;
  top: 0;
  z-index: 5;
  width: var(--yumi-rail-width);
  height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: var(--yumi-rail-padding) 0;
  background: var(--yumi-color-rail);
  border-right: none;
  overflow: hidden;
}

.brand {
  width: var(--yumi-rail-brand-size);
  height: var(--yumi-rail-brand-size);
  display: grid;
  place-items: center;
  border-radius: var(--yumi-radius-xl);
  background: var(--yumi-color-rail-brand);
  color: var(--yumi-color-rail);
  font-size: var(--yumi-font-size-subtitle);
  font-weight: var(--yumi-font-weight-bold);
  text-decoration: none;
}

.rail-nav {
  display: flex;
  flex: 1;
  min-height: 0;
  flex-direction: column;
  gap: var(--yumi-rail-gap);
  width: 100%;
  margin-top: var(--yumi-rail-nav-offset);
  align-items: center;
  overflow-y: auto;
  scrollbar-width: none;
}

.rail-nav::-webkit-scrollbar {
  display: none;
}

.rail-btn {
  width: var(--yumi-rail-item-size);
  height: var(--yumi-rail-item-size);
  display: grid;
  place-items: center;
  border-radius: var(--yumi-radius-lg);
  color: var(--yumi-color-rail-text);
}

.rail-btn:hover,
.rail-btn--active {
  background: var(--yumi-color-rail-hover);
  color: var(--yumi-color-on-dark);
}

.rail-btn:focus-visible {
  outline: 2px solid var(--yumi-color-rail-brand);
  outline-offset: 2px;
}

.rail-footer {
  margin-top: auto;
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

@media (max-width: 600px) {
  .rail {
    width: var(--yumi-rail-width-sm);
    padding: var(--yumi-space-4) 0;
  }

  .brand {
    width: var(--yumi-rail-brand-size-sm);
    height: var(--yumi-rail-brand-size-sm);
    font-size: var(--yumi-font-size-base);
  }

  .rail-btn {
    width: var(--yumi-rail-item-size-sm);
    height: var(--yumi-rail-item-size-sm);
  }

  .header {
    padding: 0 var(--yumi-space-3);
  }

  .content {
    padding: var(--yumi-space-4) var(--yumi-space-3) var(--yumi-space-6);
  }

  .account > span {
    display: none;
  }
}
</style>
