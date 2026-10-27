<script setup lang="ts">
import { useAuthStore } from '@/platform/stores/auth'

defineProps<{ permission: string }>()

const auth = useAuthStore()
</script>

<template>
  <slot v-if="auth.hasPermission(permission)" />
</template>
