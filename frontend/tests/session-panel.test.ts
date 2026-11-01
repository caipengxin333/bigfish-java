import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createSessionControls } from '../src/session-controls.ts'
import { createApi } from '../src/api.ts'
import { createRuntimeApi } from '../src/runtime-api.ts'
import * as fixtures from './session-fixtures.ts'

// Compile the real SFC with the installed Vue compiler. Only its controller factory is
// injected; SSR cannot run lifecycle polling or contact a real backend.
const componentUrl = new URL('../src/components/BackendRuntimePanel.vue', import.meta.url)
const source = await readFile(componentUrl, 'utf8')
const { descriptor } = parse(source, { filename: fileURLToPath(componentUrl) })
const script = compileScript(descriptor, { id: 'session-panel-test', inlineTemplate: true, templateOptions: { ssr: true } })
const shim = `export { collectorSummary, safeCode, sourceLabel } from ${JSON.stringify(new URL('../src/session-controls.ts', import.meta.url).href)};
export const createSessionControls = () => globalThis.__bigfishPanelControls;`
const urlFor = (source: string) => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
const compiled = ts.transpileModule(script.content, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  .replace(/from ["']([^"']+)["']/g, (_match, specifier) => `from ${JSON.stringify(specifier === '../session-controls.ts'
    ? urlFor(shim) : specifier.startsWith('.') ? new URL(specifier, componentUrl).href : import.meta.resolve(specifier))}`)
const Panel = (await import(urlFor(compiled))).default

async function render(data = structuredClone({ ...fixtures }), compact = false) {
  const fetcher = (async (url: string) => {
    const routes = { '/runtime': data.runtime, '/runtime/diagnostics': { ...data.diagnostics, sessionId: data.runtime.sessionId, state: data.runtime.state },
      '/config': data.snapshot, '/bootstrap': data.bootstrap, '/providers': data.providers }
    const key = url.replace('/api/v1', '') as keyof typeof routes
    assert.ok(key in routes, 'rendering must never mutate or connect')
    return Response.json(routes[key])
  }) as typeof fetch
  const controls = createSessionControls(createRuntimeApi(fetcher), createApi(fetcher))
  await controls.refresh()
  ;(globalThis as any).__bigfishPanelControls = controls
  const locks: boolean[] = []
  try {
    return { html: await renderToString(createSSRApp(Panel, { compact, onRoomLock: (locked: boolean) => locks.push(locked) })), locks }
  } finally { controls.dispose(); delete (globalThis as any).__bigfishPanelControls }
}
test('actual admin panel disables unavailable source, preserves token/diagnostic UI and emits unlocked room', async () => {
  const data = structuredClone({ ...fixtures }); data.providers.douyin.enabled = false; data.bootstrap.capabilities.douyinCollector = false
  const { html, locks } = await render(data)
  assert.match(html, /<option value="DOUYIN" disabled>抖音直播/)
  assert.match(html, /后端暂未开放真实接收/)
  assert.match(html, /<option value="MOCK" selected>模拟采集/)
  assert.match(html, /Token 模式：STATIC/); assert.match(html, /当前阶段：会话未运行/)
  assert.match(html, /启动不会自动保存表单/); assert.doesNotMatch(html, />启动会话<|<details[^>]* open/)
  assert.match(html, /本浏览器启动选项/); assert.deepEqual(locks, [false])
})
test('actual stage panel renders live state and room, locks source and disables all simulation buttons', async () => {
  const data = structuredClone({ ...fixtures })
  data.runtime = { ...data.runtime, sessionId: 'live-session', state: 'RUNNING', mode: 'DOUYIN', roomId: '123456',
    player: { ...data.runtime.player, connected: true, ready: true },
    collector: { ...data.runtime.collector, mode: 'DOUYIN', state: 'RECONNECTING', lastError: 'CONNECTION_CLOSED' } }
  const { html, locks } = await render(data, true)
  assert.doesNotMatch(html, /<select/) // Stage reads actual options; editing is centralized in admin.
  assert.match(html, /当前消息来源：抖音直播/)
  for (const label of ['发送模拟评论', '发送模拟礼物', '采集器连击回放']) assert.match(html, new RegExp(`<button class="secondary" disabled>${label}`))
  assert.match(html, /当前运行房间：123456/); assert.match(html, /重连中/); assert.match(html, /尚未收到采集事件/)
  assert.match(html, /CONNECTION_CLOSED/); assert.match(html, /其他页面的未保存输入不会用于连接/)
  assert.deepEqual(locks, [true])
})
