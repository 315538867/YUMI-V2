<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { userMessageOf } from '@/platform/services/errorMessage'
import { useAuthStore } from '@/platform/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const form = reactive({ loginName: '', password: '' })
const errorText = ref('')
const expired = route.query.expired === '1'

async function submit(): Promise<void> {
  errorText.value = ''
  if (form.loginName.trim() === '' || form.password === '') {
    errorText.value = '请输入登录名和口令'
    return
  }
  try {
    await auth.login(form.loginName.trim(), form.password)
    const target = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(target)
  } catch (error) {
    const message = userMessageOf(error, '登录失败，请稍后重试')
    errorText.value = message
    ElMessage.error(message)
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-card">
      <h1>YUMI 一期业务系统</h1>
      <p class="yumi-hint">使用已授权账号登录；系统不提供匿名注册。</p>
      <el-alert
        v-if="expired"
        type="warning"
        :closable="false"
        title="登录已失效，请重新登录"
        class="yumi-alert"
      />
      <el-alert
        v-if="errorText"
        type="error"
        :closable="false"
        :title="errorText"
        class="yumi-alert"
      />
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="登录名">
          <el-input v-model="form.loginName" placeholder="登录名" autocomplete="username" />
        </el-form-item>
        <el-form-item label="口令">
          <el-input
            v-model="form.password"
            placeholder="口令"
            type="password"
            show-password
            autocomplete="current-password"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" :loading="auth.loading" class="submit" @click="submit">
          登录
        </el-button>
      </el-form>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background: var(--yumi-canvas);
}

.login-card {
  width: 360px;
  background: var(--yumi-surface);
  border: 1px solid var(--yumi-border-color);
  border-radius: var(--yumi-radius-lg);
  padding: var(--yumi-space-6);
}

.submit {
  width: 100%;
}
</style>
