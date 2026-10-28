<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute, useRouter } from 'vue-router'
import ModuleTabsPage from '@/shared/components/ModuleTabsPage.vue'
import PermissionButton from '@/shared/components/PermissionButton.vue'

const route = useRoute()
const router = useRouter()

const tabs = [
  { path: '/workforce/employees', label: '员工与资格' },
  { path: '/workforce/teams', label: '小组' },
  { path: '/workforce/standards', label: '人员标准' },
  { path: '/workforce/activity-references', label: '活动引用' },
]

const tabRoutes = new Set(tabs.map((tab) => tab.path))
const showTabs = computed(() => tabRoutes.has(route.path))

const action = computed(() => {
  if (route.path === '/workforce/employees') {
    return {
      label: '新增员工',
      to: '/workforce/employees/new',
      permission: 'workforce.employee.manage',
    }
  }
  if (route.path === '/workforce/teams') {
    return { label: '新增小组', to: '/workforce/teams/new', permission: 'workforce.team.manage' }
  }
  return null
})
</script>

<template>
  <ModuleTabsPage
    v-if="showTabs"
    title="人员与小组"
    hint="维护员工资料、工种资格与执行小组"
    :tabs="tabs"
  >
    <template v-if="action" #actions>
      <PermissionButton
        :permission="action.permission"
        type="primary"
        @click="router.push(action.to)"
      >
        {{ action.label }}
      </PermissionButton>
    </template>
  </ModuleTabsPage>
  <RouterView v-else />
</template>
