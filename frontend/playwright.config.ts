import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright 配置。
 *
 * 页面测试必须使用正式 API 准备数据，不直接写数据库；服务由配置启动并在结束时清理，
 * 不复用未知的常驻服务。
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: process.env.YUMI_E2E_BASE_URL ?? 'http://127.0.0.1:4173',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
  webServer: {
    command: `YUMI_API_TARGET=${process.env.YUMI_E2E_API_TARGET ?? 'http://127.0.0.1:8080'} pnpm exec vite --port 4173 --strictPort --host 127.0.0.1`,
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: false,
    timeout: 120_000,
  },
})
