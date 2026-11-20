import { defineConfig } from '@playwright/test'

process.env.NO_PROXY = [process.env.NO_PROXY, '127.0.0.1', 'localhost'].filter(Boolean).join(',')

export default defineConfig({
  testDir: './tests',
  testMatch: 'customer-chat.spec.js',
  outputDir: './test-results/customer',
  workers: 1,
  use: { baseURL: 'http://127.0.0.1:41731', channel: process.env.PLAYWRIGHT_CHANNEL || (process.platform === 'win32' ? 'msedge' : undefined), headless: true, trace: 'retain-on-failure' },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 41731 --strictPort', url: 'http://127.0.0.1:41731/', reuseExistingServer: !process.env.CI }
})
