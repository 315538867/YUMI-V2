<script setup lang="ts">
import { Search, Bell } from '@element-plus/icons-vue'
import UiIconButton from './UiIconButton.vue'
import type { TopNavItem } from './types'

withDefaults(
  defineProps<{
    items: TopNavItem[]
    active?: string
    accountInitial?: string
    accountName?: string
    searchPlaceholder?: string
    showSearch?: boolean
    showNotifications?: boolean
    showLogout?: boolean
  }>(),
  {
    active: '',
    accountInitial: '陈',
    accountName: '',
    searchPlaceholder: '搜索任务、商品或员工',
    showSearch: true,
    showNotifications: true,
    showLogout: false,
  },
)

const emit = defineEmits<{
  select: [key: string]
  home: []
  logout: []
}>()

function handleAccountCommand(command: string): void {
  if (command === 'logout') {
    emit('logout')
  }
}
</script>

<template>
  <header class="yui-topnav">
    <button type="button" class="yui-topnav__brand" @click="$emit('home')">
      <span class="yui-topnav__mark">YU</span>
      <span class="yui-topnav__word">YUMI<small>WORKSHOP</small></span>
    </button>
    <nav class="yui-topnav__nav" aria-label="模块导航">
      <a
        v-for="item in items"
        :key="item.key"
        class="yui-topnav__item"
        :class="{ 'yui-topnav__item--active': item.key === active }"
        :href="item.path ?? '#'"
        @click.prevent="$emit('select', item.key)"
      >
        {{ item.label }}
      </a>
    </nav>
    <div class="yui-topnav__right">
      <div v-if="showSearch" class="yui-topnav__search">
        <el-icon :size="14"><Search /></el-icon>
        <span>{{ searchPlaceholder }}</span>
      </div>
      <UiIconButton v-if="showNotifications" label="通知">
        <el-icon :size="17"><Bell /></el-icon>
      </UiIconButton>
      <el-dropdown v-if="accountName" trigger="click" @command="handleAccountCommand">
        <span class="yui-topnav__avatar" :title="accountName">{{ accountInitial }}</span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="logout">退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <span v-else class="yui-topnav__avatar">{{ accountInitial }}</span>
      <button v-if="showLogout" type="button" class="yui-topnav__logout" @click="$emit('logout')">
        退出
      </button>
    </div>
  </header>
</template>
