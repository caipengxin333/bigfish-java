import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath } from 'node:url'
import { readFileSync } from 'node:fs'
export default defineConfig({
  plugins: [vue(), { name: 'cubism-license', generateBundle() {
    for (const name of ['LICENSE.md', 'SOURCE.md']) this.emitFile({ type: 'asset', fileName: `licenses/cubism-framework/${name}`, source: readFileSync(new URL(`./vendor/cubism-framework/${name}`, import.meta.url), 'utf8') })
  } }],
  resolve: { alias: { '@cubism': fileURLToPath(new URL('./.cubism-cache', import.meta.url)) } },
  server: {
    port: Number(process.env.BIGFISH_UI_PORT || 15173), strictPort: true,
    proxy: {
      '/avatar-assets': process.env.BIGFISH_API_TARGET || 'http://127.0.0.1:18082',
      '/api': process.env.BIGFISH_API_TARGET || 'http://127.0.0.1:18080',
      '/actuator': process.env.BIGFISH_API_TARGET || 'http://127.0.0.1:18080',
      '/ws': { target: process.env.BIGFISH_API_TARGET || 'http://127.0.0.1:18080', ws: true, changeOrigin: false },
    },
  },
})
