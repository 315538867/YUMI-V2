import { api } from '@/platform/services/api'

export interface LoginResponse {
  token: string
  sessionBusinessId: string
  accountBusinessId: string
  expiresAt: string
}

export interface CurrentAccount {
  accountBusinessId: string
  loginName: string
  sessionBusinessId: string
  permissions: string[]
}

/** 登录、当前账号与退出；不暴露账号管理命令。 */
export const identityService = {
  login(loginName: string, password: string): Promise<LoginResponse> {
    return api.sessionCommand<LoginResponse>('/api/platform/identity/sessions', {
      loginName,
      password,
    })
  },

  me(): Promise<CurrentAccount> {
    return api.query<CurrentAccount>('/api/platform/identity/me')
  },

  async logout(): Promise<void> {
    await api.sessionCommand<void>('/api/platform/identity/sessions/logout')
  },
}
