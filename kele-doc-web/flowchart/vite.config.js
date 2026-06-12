import { defineConfig } from 'vite'

export default defineConfig({
  root: 'src/main/webapp',
  server: {
    host: '0.0.0.0',
    port: 9097,
    proxy: {
      '^/api': {
        target: 'http://localhost:9222/',
        changeOrigin: true
      },
      '^/static': {
        target: 'http://localhost:9222/',
        changeOrigin: true
      }
    }
  }
})
