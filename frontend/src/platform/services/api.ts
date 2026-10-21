import { createApiClient, type ApiClientError } from '@/platform/services/apiClient'
import { getSessionToken } from '@/platform/services/session'

let unauthorizedHandler: ((error: ApiClientError) => void) | null = null

/** 注册会话失效处理（认证 store 在应用启动时注册一次）。 */
export function onSessionExpired(handler: (error: ApiClientError) => void): void {
  unauthorizedHandler = handler
}

/**
 * 全站唯一 API 客户端。
 *
 * 业务模块只在自己的 `services` 中调用本客户端；页面不得直接导入。
 */
export const api = createApiClient({
  tokenProvider: getSessionToken,
  onUnauthorized: (error) => unauthorizedHandler?.(error),
})
