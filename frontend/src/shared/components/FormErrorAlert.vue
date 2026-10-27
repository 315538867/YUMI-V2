<script setup lang="ts">
defineProps<{ error?: string }>()
</script>

<template>
  <el-alert v-if="error" type="error" :closable="false" :title="error" />
</template>
