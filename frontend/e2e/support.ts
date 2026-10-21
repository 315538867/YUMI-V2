import { expect, type APIRequestContext, type Page } from '@playwright/test'

export const ADMIN_LOGIN = process.env.YUMI_E2E_ADMIN_LOGIN ?? 'it-admin'
export const ADMIN_PASSWORD = process.env.YUMI_E2E_ADMIN_PASSWORD ?? 'it-pass-1234'

export async function loginViaApi(request: APIRequestContext): Promise<string> {
  const response = await request.post('/api/platform/identity/sessions', {
    data: { loginName: ADMIN_LOGIN, password: ADMIN_PASSWORD },
  })
  expect(response.ok()).toBeTruthy()
  const body = (await response.json()) as { token: string }
  return body.token
}

export async function login(page: Page): Promise<void> {
  await page.goto('/login')
  await page.getByPlaceholder('登录名').fill(ADMIN_LOGIN)
  await page.getByPlaceholder('口令').fill(ADMIN_PASSWORD)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/$/)
}

export function authHeaders(token: string, idempotencyKey?: string): Record<string, string> {
  return {
    Authorization: `Bearer ${token}`,
    ...(idempotencyKey === undefined ? {} : { 'Idempotency-Key': idempotencyKey }),
  }
}
