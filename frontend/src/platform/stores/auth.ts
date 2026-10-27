import { defineStore } from 'pinia'
import { ref } from 'vue'
import { identityService, type CurrentAccount } from '@/platform/services/identityService'
import { clearSessionToken, getSessionToken, setSessionToken } from '@/platform/services/session'

/**
 * 登录会话状态。
 *
 * 权限最终由后端判定；前端只缓存当前账号权限用于导航可见性，不作为安全边界。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(getSessionToken())
  const account = ref<CurrentAccount | null>(null)
  const loading = ref(false)

  async function login(loginName: string, password: string): Promise<void> {
    loading.value = true
    try {
      const session = await identityService.login(loginName, password)
      setSessionToken(session.token)
      token.value = session.token
      account.value = await identityService.me()
    } finally {
      loading.value = false
    }
  }

  async function restore(): Promise<void> {
    if (token.value === null) {
      return
    }
    try {
      account.value = await identityService.me()
    } catch {
      clear()
    }
  }

  async function logout(): Promise<void> {
    try {
      if (token.value !== null) {
        await identityService.logout()
      }
    } finally {
      clear()
    }
  }

  function clear(): void {
    clearSessionToken()
    token.value = null
    account.value = null
  }

  function hasPermission(permission: string): boolean {
    return account.value?.permissions.includes(permission) ?? false
  }

  return { token, account, loading, login, restore, logout, clear, hasPermission }
})
