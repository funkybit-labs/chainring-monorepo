/// <reference types="vitest" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react-swc'
import tsconfigPaths from 'vite-tsconfig-paths'

// to fix 'Buffer is not defined' error, solution taken from https://stackoverflow.com/a/77153849
import { nodePolyfills } from 'vite-plugin-node-polyfills'

// https://vitejs.dev/config https://vitest.dev/config
export default defineConfig({
  server: {
    port: 3000
  },
  plugins: [react(), tsconfigPaths(), nodePolyfills()],
  test: {
    globals: true,
    environment: 'happy-dom',
    setupFiles: '.vitest/setup',
    include: ['**/test.{ts,tsx}']
  },
  envPrefix: 'ENV_'
})
