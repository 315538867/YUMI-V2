<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

/**
 * 模块入口占位：仅证明导航与首个动作入口可达。
 * 业务模块页面在各自任务中替换本组件，不在此伪造业务数字或模板表达式。
 */
const route = useRoute()
const title = computed(() => String(route.meta.title ?? '业务模块'))
</script>

<template>
  <section class="module-placeholder">
    <h1>{{ title }}</h1>
    <p>入口已接入统一导航；业务列表、详情与操作将在对应模块任务中接入真实接口。</p>
  </section>
</template>

<style scoped>
.module-placeholder {
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 24px;
}

h1 {
  margin: 0 0 8px;
  font-size: 20px;
  font-weight: 600;
}

p {
  margin: 0;
  color: var(--el-text-color-regular);
}
</style>
