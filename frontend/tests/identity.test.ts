import assert from 'node:assert/strict'
import test from 'node:test'
import { reactive } from 'vue'
import { createIdentityEditor, IdentityError, identityFailure } from '../src/identity/editor.ts'
import type { IdentityPort, IdentityStatus } from '../src/identity/editor.ts'
import { createIdentityDemo, identityDemoKey } from '../src/mock/identity.ts'

function rig(timeout = 100) {
  let raw: string | null = null, time = 1_800_000_000_000
  const storage = { getItem: (_key: string) => raw, setItem: (key: string, value: string) => { assert.equal(key, identityDemoKey); raw = value } }
  const demo = createIdentityDemo(() => storage, () => time, 1)
  const context = reactive({ locked: false, roomId: '123456', roomPending: false })
  const editor = createIdentityEditor(demo.port, () => context, timeout)
  return { demo, context, editor, raw: () => raw!, setRaw: (value: string) => { raw = value }, advance: (ms: number) => { time += ms } }
}
async function setupCookie(r: ReturnType<typeof rig>) {
  await r.editor.refresh(); r.editor.choice.value = 'cookie'; r.editor.agent.value = 'FAKE_UA_NOT_A_BROWSER'; await r.editor.saveOptions()
  assert.equal(r.editor.error.value, '')
}
test('default visitor and reads never start a check; options are separate from cookie and business config', async () => {
  const r = rig(); await r.editor.refresh()
  assert.equal(r.editor.choice.value, 'visitor'); assert.equal(r.editor.status.value?.configured, false); assert.equal(r.editor.checkResult.value, null)
  r.editor.choice.value = 'cookie'; r.editor.cookie.value = 'FAKE_COOKIE_DRAFT'; await r.editor.saveCookie()
  assert.match(r.editor.error.value, /先保存接入选项/)
  r.editor.agent.value = 'FAKE_UA_NOT_A_BROWSER'; await r.editor.saveOptions()
  assert.equal(r.editor.cookie.value, 'FAKE_COOKIE_DRAFT'); assert.equal(r.editor.status.value?.configured, false)
  assert.ok(!r.raw().includes('FAKE_')); r.editor.dispose()
})
test('failed cookie save preserves draft; success clears it; metadata never contains cookie, UA or manual ID', async () => {
  const r = rig(); await setupCookie(r)
  r.editor.manual.value = true; r.editor.visitorId.value = '112233445566'; await r.editor.saveOptions()
  r.editor.cookie.value = 'FAKE_COOKIE_ONLY'; r.demo.setWriteFailure(true); await r.editor.saveCookie()
  assert.equal(r.editor.cookie.value, 'FAKE_COOKIE_ONLY'); assert.match(r.editor.error.value, /保存失败/)
  r.demo.setWriteFailure(false); await r.editor.saveCookie()
  assert.equal(r.editor.cookie.value, ''); assert.equal(r.editor.status.value?.configured, true)
  assert.ok(!/FAKE_|112233445566|userAgent|visitorId|secret/.test(r.raw()))
  assert.match(r.editor.notice.value, /不代表平台登录有效/); r.editor.dispose()
})
test('empty cookie never replaces saved credentials; clear requires an explicit confirmation and retains selected mode', async () => {
  const r = rig(); await setupCookie(r); r.editor.cookie.value = 'FAKE_ONE'; await r.editor.saveCookie()
  const before = r.raw(); await r.editor.saveCookie(); assert.equal(r.raw(), before); assert.match(r.editor.error.value, /空输入不会覆盖/)
  r.advance(100); r.editor.cookie.value = 'FAKE_TWO'; await r.editor.saveCookie(); assert.notEqual(r.raw(), before)
  await r.editor.clearCookie(true); assert.equal(r.editor.status.value?.configured, true)
  r.editor.clearConfirm.value = true; await r.editor.clearCookie(false); assert.equal(r.editor.status.value?.configured, true)
  await r.editor.clearCookie(true); assert.equal(r.editor.status.value?.configured, false); assert.equal(r.editor.choice.value, 'cookie'); r.editor.dispose()
})
test('switching to visitor does not clear credentials and does not check automatically', async () => {
  const r = rig(); await setupCookie(r); r.editor.cookie.value = 'FAKE'; await r.editor.saveCookie()
  r.editor.choice.value = 'visitor'; await r.editor.saveOptions()
  assert.equal(r.editor.status.value?.configured, true); assert.equal(r.editor.checkResult.value, null); r.editor.dispose()
})
test('running or unknown runtime locks all writes and connection checks', async () => {
  const r = rig(); await setupCookie(r); const before = r.raw()
  r.context.locked = true; r.editor.cookie.value = 'FAKE'; r.editor.clearConfirm.value = true
  for (const action of [r.editor.saveOptions, r.editor.saveCookie, () => r.editor.clearCookie(true), r.editor.check]) { await action(); assert.match(r.editor.error.value, /先停止互动/) }
  assert.equal(r.raw(), before); assert.equal(r.editor.canEdit.value, false); r.editor.dispose()
})
test('refresh restores metadata only; simulated backend restart resets status and mode', async () => {
  const r = rig(); await setupCookie(r); r.editor.cookie.value = 'FAKE'; await r.editor.saveCookie()
  const reloaded = createIdentityEditor(r.demo.port, () => r.context)
  await reloaded.refresh(); assert.equal(reloaded.status.value?.configured, true); assert.equal(reloaded.cookie.value, ''); assert.equal(reloaded.agent.value, '')
  r.demo.restart(); await reloaded.refresh(); assert.equal(reloaded.status.value?.configured, false); assert.equal(reloaded.choice.value, 'visitor')
  assert.match(reloaded.notice.value, /重置/); r.editor.dispose(); reloaded.dispose()
})
test('connection success, comments and gifts are independent evidence; missing gifts never proves lack of permission', async () => {
  const r = rig(); await r.editor.refresh()
  for (const kind of ['connected', 'comments', 'gifts'] as const) {
    r.demo.setScenario(kind); await r.editor.check()
    assert.equal(r.editor.checkResult.value?.kind, kind)
    assert.equal(r.editor.checkResult.value?.gifts, kind === 'gifts' ? 1 : 0)
  }
  r.demo.setScenario('failed'); await r.editor.check(); assert.equal(r.editor.checkResult.value, null); assert.match(r.editor.error.value, /连接检查失败/)
  r.editor.dispose()
})
test('automatic visitor ID failure opens a manual remedy; invalid/manual values are not persisted', async () => {
  const r = rig(); await r.editor.refresh(); r.demo.setScenario('id-required'); await r.editor.check()
  assert.equal(r.editor.needsId.value, true); assert.match(r.editor.error.value, /user_unique_id/)
  r.editor.manual.value = true; r.editor.visitorId.value = 'https://invalid'; await r.editor.saveOptions(); assert.match(r.editor.error.value, /格式/)
  r.editor.visitorId.value = '12345'; await r.editor.saveOptions(); await r.editor.check()
  assert.equal(r.editor.checkResult.value?.kind, 'connected'); assert.ok(!r.raw().includes('12345')); r.editor.dispose()
})
test('unsaved or empty room blocks checks, with no implicit config save', async () => {
  const r = rig(); await r.editor.refresh(); const before = r.raw()
  r.context.roomPending = true; await r.editor.check(); assert.match(r.editor.error.value, /保存房间号/)
  r.context.roomPending = false; r.context.roomId = ''; await r.editor.check(); assert.match(r.editor.error.value, /保存房间号/)
  assert.equal(r.raw(), before); r.editor.dispose()
})
test('timeout cancels and clears successful evidence; it preserves draft and never retries automatically', async () => {
  const r = rig(20); await r.editor.refresh(); await r.editor.check(); r.editor.cookie.value = 'FAKE_DRAFT'
  r.demo.setScenario('timeout'); await r.editor.check()
  assert.match(r.editor.error.value, /超时/); assert.equal(r.editor.checkResult.value, null); assert.equal(r.editor.status.value, null); assert.equal(r.editor.cookie.value, 'FAKE_DRAFT')
  await r.editor.refresh(); assert.ok(r.editor.status.value); r.editor.dispose()
})
test('read failures do not keep stale configured/success UI and unknown errors are sanitized', async () => {
  const r = rig(); await setupCookie(r); r.editor.cookie.value = 'FAKE'; await r.editor.saveCookie(); await r.editor.check()
  r.demo.setReadFailure(true); await r.editor.refresh(); assert.equal(r.editor.status.value, null); assert.equal(r.editor.checkResult.value, null)
  assert.match(r.editor.error.value, /读取失败/); assert.ok(!identityFailure(new Error('SENSITIVE_RESPONSE')).includes('SENSITIVE'))
  assert.ok(!identityFailure(new IdentityError('constructor')).includes('function')); r.editor.dispose()
})
test('expired metadata is unconfigured; changing retention does not extend an existing Cookie', async () => {
  const r = rig(); await setupCookie(r); r.editor.cookie.value = 'FAKE'; await r.editor.saveCookie()
  const expiry = r.editor.status.value!.expiresAt
  r.editor.retentionMinutes.value = 1440; await r.editor.saveOptions(); assert.equal(r.editor.status.value!.expiresAt, expiry)
  r.advance(60 * 60_000); await r.editor.refresh(); assert.equal(r.editor.status.value?.configured, false)
  await r.editor.check(); assert.match(r.editor.error.value, /尚未配置有效 Cookie/); r.editor.dispose()
})
test('late save after cancellation cannot clear draft or restore old status; disposal erases sensitive fields', async () => {
  const r = rig(); await setupCookie(r)
  let resolve!: (v: IdentityStatus) => void, calls = 0
  const port: IdentityPort = { ...r.demo.port, replace: async () => { calls++; return new Promise(done => { resolve = done }) } }
  const e = createIdentityEditor(port, () => r.context); await e.refresh(); e.cookie.value = 'FAKE_PENDING'
  const saving = e.saveCookie(); assert.equal(calls, 1); void e.saveCookie(); assert.equal(calls, 1)
  e.invalidate(); resolve({ ...r.editor.status.value!, configured: true }); await saving
  assert.equal(e.status.value, null); assert.equal(e.cookie.value, 'FAKE_PENDING'); e.dispose(); assert.equal(e.cookie.value, ''); r.editor.dispose()
})
test('another page update is a conflict; corrupt storage is rejected, not silently trusted', async () => {
  const r = rig(); await setupCookie(r)
  const second = createIdentityEditor(r.demo.port, () => r.context); await second.refresh()
  r.editor.choice.value = 'visitor'; await r.editor.saveOptions()
  second.cookie.value = 'FAKE'; await second.saveCookie(); assert.match(second.error.value, /另一页面/); assert.equal(second.cookie.value, 'FAKE')
  r.setRaw('{malformed'); await r.editor.refresh(); assert.equal(r.editor.status.value, null); assert.match(r.editor.error.value, /读取失败/)
  second.dispose(); r.editor.dispose()
})
test('unwired real mode cannot send mutations or silently instantiate mock', async () => {
  const e = createIdentityEditor(null, () => ({ locked: false, roomId: '123', roomPending: false }))
  await e.refresh(); assert.equal(e.status.value, null); e.cookie.value = 'FAKE'; await e.saveCookie()
  assert.match(e.error.value, /公共契约尚未接入/); assert.equal(e.canEdit.value, false); e.dispose()
})
test('parent context changes recover an interrupted initial read and discard old-room checks without retrying', async () => {
  const r = rig(); await r.editor.refresh()
  let reads = 0, checks = 0, oldSignal!: AbortSignal, oldRead!: (v: IdentityStatus) => void, oldCheck!: (v: { kind: 'gifts'; comments: number; gifts: number }) => void
  const e = createIdentityEditor({ ...r.demo.port,
    read: signal => ++reads === 1 ? new Promise(done => { oldRead = done; oldSignal = signal }) : r.demo.port.read(signal),
    check: async () => { checks++; return new Promise(done => { oldCheck = done }) },
  }, () => r.context)
  const loading = e.refresh(); r.context.roomId = '654321'; await e.contextChanged()
  assert.equal(oldSignal.aborted, true); assert.ok(e.status.value); assert.equal(e.canEdit.value, true)
  oldRead({ ...r.editor.status.value!, configured: true }); await loading
  assert.equal(e.status.value?.configured, false)
  e.cookie.value = 'FAKE_UNSUBMITTED'; const checking = e.check(); r.context.roomId = '456789'; await e.contextChanged()
  oldCheck({ kind: 'gifts', comments: 0, gifts: 1 }); await checking
  assert.equal(checks, 1); assert.equal(e.checkResult.value, null); assert.equal(e.cookie.value, 'FAKE_UNSUBMITTED')
  assert.equal(e.error.value, ''); e.dispose(); r.editor.dispose()
})
