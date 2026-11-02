import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
import { parse, compileScript } from '@vue/compiler-sfc'
import ts from 'typescript'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createIdentityEditor } from '../src/identity/editor.ts'
import { createIdentityDemo } from '../src/mock/identity.ts'
const componentUrl = new URL('../src/components/DouyinIdentityPanel.vue', import.meta.url)
const urlFor = (value: string) => `data:text/javascript;base64,${Buffer.from(value).toString('base64')}`
async function compileComponent(url: URL): Promise<string> {
  const { descriptor } = parse(await readFile(url, 'utf8'), { filename: fileURLToPath(url) })
  const script = compileScript(descriptor, { id: 'identity-panel-test', inlineTemplate: true, templateOptions: { ssr: true } })
  let compiled = ts.transpileModule(script.content, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  for (const match of [...compiled.matchAll(/from ["']([^"']+)["']/g)]) {
    const specifier = match[1]!
    const target = specifier === '../identity/editor.ts'
      ? urlFor('export const createIdentityEditor = () => globalThis.__identityTestEditor;')
      : specifier.endsWith('.vue') ? await compileComponent(new URL(specifier, url))
      : specifier.startsWith('.') ? new URL(specifier, url).href : import.meta.resolve(specifier)
    compiled = compiled.replace(match[0], `from ${JSON.stringify(target)}`)
  }
  return urlFor(compiled)
}
const Panel = (await import(await compileComponent(componentUrl))).default
async function withEditor(run: (editor: ReturnType<typeof createIdentityEditor>, demo: ReturnType<typeof createIdentityDemo>, render: (locked?: boolean, real?: boolean) => Promise<string>) => Promise<void>) {
  let raw: string | null = null
  const demo = createIdentityDemo(() => ({ getItem: () => raw, setItem: (_key, value) => { raw = value } }), Date.now, 0)
  const context = { locked: false, roomId: '123', roomPending: false }, editor = createIdentityEditor(demo.port, () => context)
  await editor.refresh()
  const render = async (locked = false, real = false) => {
    context.locked = locked
    ;(globalThis as any).__identityTestEditor = real ? createIdentityEditor(null, () => context) : editor
    return renderToString(createSSRApp(Panel, { demo: !real, locked, roomId: '123', roomPending: false }))
  }
  try { await run(editor, demo, render) } finally { editor.dispose(); delete (globalThis as any).__identityTestEditor }
}
test('real panel waits for confirmed backend state and never falls back to mock', async () => {
  await withEditor(async (_editor, _demo, render) => {
    const html = await render(false, true)
    assert.match(html, /REAL · 后端内存配置/); assert.match(html, /后端身份状态尚未确认/)
    assert.match(html, /<fieldset disabled/); assert.doesNotMatch(html, /type="password"|后端已保存|已登录成功/)
    assert.match(html, /检查连接（20 秒）/); assert.match(html, /webcast\/room\/web\/enter/)
    assert.doesNotMatch(html, /检查连接（MOCK）|真实接口 · 待接入/)
  })
})
test('actual Cookie panel uses password input, separate forms and clear confirmation, without login claims', async () => {
  await withEditor(async (e, _demo, render) => {
    e.choice.value = 'cookie'; e.agent.value = 'FAKE_AGENT'; await e.saveOptions()
    e.cookie.value = 'FAKE_COOKIE'; await e.saveCookie()
    const html = await render()
    assert.equal((html.match(/<form /g) ?? []).length, 2)
    assert.match(html, /type="password"/); assert.match(html, /autocomplete="off"/); assert.match(html, /已配置（未证明登录有效）/)
    assert.doesNotMatch(html, /FAKE_COOKIE|FAKE_AGENT|已登录成功|>确认清除 Cookie</)
    e.clearConfirm.value = true; assert.match(await render(), />确认清除 Cookie</)
  })
})
test('running panel locks edits/clearing and explains stop first', async () => {
  await withEditor(async (_e, _demo, render) => {
    const html = await render(true)
    assert.match(html, /先停止互动/); assert.match(html, /<fieldset disabled/)
    assert.match(html, /<button[^>]*disabled[^>]*>清除 Cookie/)
    assert.match(html, /<button[^>]*disabled[^>]*>检查连接（MOCK）/)
  })
})
test('actual check result distinguishes comments/gifts and includes Safari document-request workaround', async () => {
  await withEditor(async (e, demo, render) => {
    demo.setScenario('comments'); await e.check(); const html = await render()
    assert.match(html, /已收到评论（演练样本）/); assert.match(html, /尚未观察到礼物，不能据此判定无礼物权限/)
    assert.match(html, /没有请求 Cookie/); assert.match(html, /直播间页面文稿请求 → 标头 → 请求 → Cookie/)
    assert.match(html, /webcast\/im\/push\/v2/); assert.match(html, /不上传完整 HAR/)
    demo.setScenario('gifts'); await e.check(); assert.match(await render(), /已收到礼物（演练样本）/)
    demo.setScenario('failed'); await e.check(); const failed = await render()
    assert.ok(failed.indexOf('连接检查失败') > failed.indexOf('检查连接（MOCK）'))
    assert.equal((failed.match(/role="alert"/g) ?? []).length, 1)
  })
})
