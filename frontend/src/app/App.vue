<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterView, useRouter } from 'vue-router'
import { onSessionExpired } from '@/platform/services/api'
import { useAuthStore } from '@/platform/stores/auth'

const router = useRouter()
const auth = useAuthStore()

onMounted(() => {
  onSessionExpired(() => {
    auth.clear()
    void router.replace({ name: 'login', query: { expired: '1' } })
  })
})
</script>

<template>
  <div class="yumi-app">
    <RouterView />
  </div>
</template>

<style scoped>
.yumi-app {
  min-height: 100vh;
}
</style>
