import assert from 'node:assert/strict'
import test from 'node:test'
import type { DouyinCredentialStatus, DouyinConnectionCheck } from '../src/contracts.ts'
import { AccessError, createIdentityApi, isCredentialStatus, isConnectionCheck, type IdentityApi } from '../src/identity/api.ts'
import { createManagedIdentity } from '../src/identity/managed.ts'
const rev = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', next = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
const status = (patch: Partial<DouyinCredentialStatus> = {}): DouyinCredentialStatus => ({ mode: 'GUEST', source: 'DEFAULT', version: 0, revision: rev,
  updatedAt: '2026-11-03T00:00:00Z', cookieConfigured: false, localExpiresAt: null, validation: 'GUEST_AUTO', identityMode: 'AUTO', activeUse: 'NONE', activeVersion: null, ...patch })
const check = (patch: Partial<DouyinConnectionCheck> = {}): DouyinConnectionCheck => ({ checkId: next, configVersion: 0, configRevision: rev,
  source: 'DEFAULT', mode: 'GUEST', localValidation: 'GUEST_AUTO', state: 'COMPLETED', reason: 'OBSERVATION_COMPLETE', cleanupError: '',
  connected: true, comments: 2, gifts: 1, startedAt: '2026-11-03T00:00:00Z', deadline: '2026-11-03T00:00:20Z', resourcesReleased: true, ...patch })
function api(patch: Partial<IdentityApi> = {}): IdentityApi {
  return { read: async () => status(), guest: async () => status({ revision: next, source: 'RUNTIME' }),
    replace: async () => status({ mode: 'EXTERNAL', cookieConfigured: true, revision: next, validation: 'LOCAL_VALID', identityMode: 'AUTO' }),
    clear: async () => status({ revision: next }), start: async () => check(), check: async () => check(), cancel: async () => check({ state: 'CANCELLED' }), ...patch }
}
const context = () => ({ locked: false, roomId: '123456', roomPending: false })
test('real API uses same-origin no-store guarded requests with atomic whitelisted fields', async () => {
  const calls: RequestInit[] = []
  const client = createIdentityApi(async (url, init) => {
    assert.equal(url, '/api/v1/douyin/credentials/cookie'); calls.push(init!)
    return new Response(JSON.stringify(status()), { status: 200 })
  })
  await client.replace({ expectedRevision: rev, replace: true, cookie: 'fake_cookie=value', userAgent: 'Fixture/1.0', localLifetimeSeconds: 3600 })
  assert.equal(calls.length, 1); assert.equal(calls[0]?.method, 'PUT'); assert.equal(calls[0]?.cache, 'no-store')
  assert.equal((calls[0]?.headers as Record<string, string>)['X-BigFish-Local-Management'], '1')
  const body = JSON.parse(String(calls[0]?.body)); assert.equal(body.localLifetimeSeconds, 3600); assert.equal(body.userUniqueIdOverride, undefined)
})
test('real API fails closed on missing endpoint, malformed response, timeout and credential echo', async () => {
  assert.equal(isCredentialStatus({ ...status(), cookie: 'must-not-return' }), false)
  assert.equal(isConnectionCheck({ ...check(), gifts: -1 }), false)
  for (const [response, code] of [[new Response('fake-sensitive-body', { status: 404 }), 'API_UNAVAILABLE'],
    [new Response(JSON.stringify({ ...status(), cookie: 'fake' })), 'INVALID_RESPONSE']] as const) {
    await assert.rejects(createIdentityApi(async () => response).read(), new RegExp(code))
  }
  const timed = createIdentityApi((_url, init) => new Promise((_resolve, reject) => init?.signal?.addEventListener('abort', () => reject(new Error('fake-sensitive-error')))), 5)
  await assert.rejects(timed.read(), /REQUEST_TIMEOUT/)
})
test('initial EXTERNAL state displays correctly without secrets and blank replace cannot erase cookie', async () => {
  let replacements = 0
  const e = createManagedIdentity(api({ read: async () => status({ mode: 'EXTERNAL', cookieConfigured: true }), replace: async () => { replacements++; return status() } }), context)
  await e.refresh(); assert.equal(e.mode.value, 'EXTERNAL'); assert.equal(e.dirty.value, false); assert.equal(e.cookie.value, '')
  await e.save(); assert.equal(replacements, 0); assert.match(e.error.value, /同时填写/); e.dispose()
})
test('atomic cookie replacement supports auto visitor and clears input only on success', async () => {
  let input: unknown
  const e = createManagedIdentity(api({ replace: async value => { input = value; return status({ mode: 'EXTERNAL', cookieConfigured: true, revision: next }) } }), context)
  await e.refresh(); e.mode.value = 'EXTERNAL'; e.cookie.value = 'fixture_cookie=value; test=1'; e.agent.value = 'Fixture/1.0 (Mac)'
  await e.save(); assert.equal((input as any).userUniqueIdOverride, undefined); assert.equal((input as any).cookie, 'fixture_cookie=value; test=1')
  assert.equal(e.cookie.value, ''); assert.equal(e.agent.value, ''); assert.equal(e.dirty.value, false); assert.match(e.notice.value, /尚未验证/); e.dispose()
})
test('newline and manual visitor validation block writes; conflict requires fresh read', async () => {
  let writes = 0
  const e = createManagedIdentity(api({ replace: async () => { writes++; throw new AccessError('CREDENTIAL_CONFIG_CONFLICT') } }), context)
  await e.refresh(); e.mode.value = 'EXTERNAL'; e.cookie.value = 'fixture\nmalformed'; e.agent.value = 'Fixture/1'
  await e.save(); assert.equal(writes, 0)
  e.cookie.value = 'fixture=ok'; e.manual.value = true; e.visitor.value = 'bad'; await e.save(); assert.equal(writes, 0)
  e.manual.value = false; e.visitor.value = ''; await e.save(); assert.equal(writes, 1); assert.equal(e.status.value, null)
  assert.equal(e.cookie.value, 'fixture=ok'); assert.match(e.error.value, /另一页面/); assert.equal(e.canEdit.value, false); e.dispose()
})
test('guest and confirmed clear remove runtime selection, and active lease blocks writes', async () => {
  let cleared = 0
  const e = createManagedIdentity(api({ clear: async () => { cleared++; return status({ revision: next, source: 'RUNTIME' }) } }), context)
  await e.refresh(); await e.clear(); assert.equal(cleared, 0)
  e.confirmClear.value = true; await e.clear(); assert.equal(cleared, 1); assert.equal(e.mode.value, 'GUEST')
  e.status.value = status({ activeUse: 'INTERACTION', activeVersion: 0 }); e.confirmClear.value = true; await e.clear(); assert.equal(cleared, 1); e.dispose()
})
test('bounded check distinguishes comment/gift evidence and binds results to saved revision', async () => {
  const e = createManagedIdentity(api(), context, 1)
  await e.refresh(); await e.check(); assert.equal(e.result.value?.gifts, 1); assert.equal(e.result.value?.resourcesReleased, true)
  e.manual.value = true; e.mode.value = 'EXTERNAL'; await e.check(); assert.match(e.error.value, /先保存/); e.dispose()
  let reads = 0
  const changed = createManagedIdentity(api({ read: async () => status({ revision: reads++ === 0 ? rev : next }) }), context, 1)
  await changed.refresh(); await changed.check(); assert.equal(changed.result.value, null); assert.match(changed.notice.value, /已变化/); changed.dispose()
})
test('leaving during start cancels that exact check and never accepts its late result', async () => {
  let resolve!: (value: DouyinConnectionCheck) => void; const cancellations: string[] = []
  const e = createManagedIdentity(api({ start: async () => new Promise(r => { resolve = r }), cancel: async id => { cancellations.push(id); return check() } }), context, 1)
  await e.refresh(); const task = e.check(); e.leave(); resolve(check()); await task
  assert.deepEqual(cancellations, [next]); assert.equal(e.result.value, null); assert.equal(e.status.value, null); e.dispose()
})
