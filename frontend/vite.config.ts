import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Same-origin in development, so the browser never sees a cross-origin request and
      // the CORS configuration is exercised only where it actually matters: production.
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'lcov'],
      // PER-FILE, not aggregate. An aggregate threshold lets a well-tested file carry an
      // untested one, and the untested one is usually the newest and most dangerous.
      thresholds: {
        perFile: true,
        statements: 80,
        branches: 80,
        functions: 80,
        lines: 80,
      },
      // REQUIRED. Vitest 4 removed `coverage.all` and gates the untested-file sweep on `include`,
      // which has no default — without it, a file no test imports is absent from the report and
      // the per-file threshold never sees it. See WEB-04 § Verification.
      include: ['src/**', 'scripts/**'],
      // Generated code and test helpers live under `src/`. The config and .d.ts entries match
      // nothing today; they are kept for the files that will, such as src/vite-env.d.ts.
      exclude: ['src/api/generated/**', 'src/test/**', '**/*.config.*', '**/*.d.ts'],
    },
  },
})
