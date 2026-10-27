<script setup lang="ts">
import { useAuthStore } from '@/platform/stores/auth'

defineOptions({ inheritAttrs: false })

defineProps<{ permission: string }>()

const auth = useAuthStore()
</script>

<template>
  <el-button v-if="auth.hasPermission(permission)" v-bind="$attrs">
    <slot />
  </el-button>
</template>
