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
      <p class="hint">使用已授权账号登录；系统不提供匿名注册。</p>
      <el-alert
        v-if="expired"
        type="warning"
        :closable="false"
        title="登录已失效，请重新登录"
        class="alert"
      />
      <el-alert v-if="errorText" type="error" :closable="false" :title="errorText" class="alert" />
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
  background: #f7f8fa;
}

.login-card {
  width: 360px;
  background: #fff;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 28px;
}

h1 {
  margin: 0 0 4px;
  font-size: 20px;
}

.hint {
  margin: 0 0 20px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.alert {
  margin-bottom: 12px;
}

.submit {
  width: 100%;
}
</style>
