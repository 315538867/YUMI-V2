import { ApiClientError } from '@/platform/services/apiClient'

/** 页面统一错误文案：保留后端中文消息，网络失败给出可重试提示。 */
export function userMessageOf(error: unknown, fallback = '操作失败，请稍后重试'): string {
  return error instanceof ApiClientError ? error.message : fallback
}

/** 页面只按稳定业务错误码区分空态，不直接依赖 HTTP 状态或 apiClient。 */
export function isErrorCode(error: unknown, code: string): boolean {
  return error instanceof ApiClientError && error.code === code
}
