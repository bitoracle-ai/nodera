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
      // Vitest REPLACES its default excludes when this is given, so build output has to be
      // named explicitly. Without `dist/**` the gate passes on a clean tree and fails the moment
      // anyone has run `yarn build` first — an order-dependent gate nobody ends up trusting.
      exclude: [
        'dist/**',
        'coverage/**',
        'node_modules/**',
        'src/api/generated/**',
        'src/test/**',
        '**/*.config.*',
        '**/*.d.ts',
      ],
    },
  },
})
