import { ApiClientError } from '@/platform/services/apiClient'

/** 页面统一错误文案：保留后端中文消息，网络失败给出可重试提示。 */
export function userMessageOf(error: unknown, fallback = '操作失败，请稍后重试'): string {
  return error instanceof ApiClientError ? error.message : fallback
}
