const { defineConfig } = require('@playwright/test')

process.env.NO_PROXY = [process.env.NO_PROXY, '127.0.0.1', 'localhost'].filter(Boolean).join(',')

module.exports = defineConfig({
  testDir: './tests',
  testMatch: 'customer-tasks.spec.cjs',
  outputDir: './test-results/customer',
  workers: 1,
  use: { baseURL: 'http://127.0.0.1:41732', channel: process.env.PLAYWRIGHT_CHANNEL || (process.platform === 'win32' ? 'msedge' : undefined), headless: true, viewport: { width: 1440, height: 1080 }, trace: 'retain-on-failure' },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 41732 --strictPort', url: 'http://127.0.0.1:41732/admin/', reuseExistingServer: !process.env.CI }
})
