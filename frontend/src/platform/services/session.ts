const TOKEN_KEY = 'yumi.session.token'

/** 会话令牌只保存在浏览器本地；页面不得直接读写，统一经 identity service/store。 */
export function getSessionToken(): string | null {
  try {
    return globalThis.localStorage?.getItem(TOKEN_KEY) ?? null
  } catch {
    return null
  }
}

export function setSessionToken(token: string): void {
  try {
    globalThis.localStorage?.setItem(TOKEN_KEY, token)
  } catch {
    // 存储不可用时仅本次页面内存态有效，登录仍可完成
  }
}

export function clearSessionToken(): void {
  try {
    globalThis.localStorage?.removeItem(TOKEN_KEY)
  } catch {
    // 忽略存储异常
  }
}
