import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({
  plugins: [vue()],
  server: {
    port: Number(process.env.BIGFISH_UI_PORT || 15173), strictPort: true,
    proxy: {
      '/api': process.env.BIGFISH_API_TARGET || 'http://127.0.0.1:18080',
      '/actuator': process.env.BIGFISH_API_TARGET || 'http://127.0.0.1:18080',
      '/ws': { target: process.env.BIGFISH_API_TARGET || 'http://127.0.0.1:18080', ws: true, changeOrigin: false },
    },
  },
})
