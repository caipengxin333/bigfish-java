import assert from 'node:assert/strict'
import test from 'node:test'
import { reactive } from 'vue'
import { createSessionControls, collectorSummary, douyinAvailability, safeCode } from '../src/session-controls.ts'
import { createApi, RequestError } from '../src/api.ts'
import { createRuntimeApi } from '../src/runtime-api.ts'
import type { RoomDraft } from '../src/session-controls.ts'
import type { CollectionMode } from '../src/contracts.ts'
import * as fixtures from './session-fixtures.ts'

function fixture(draft?: () => RoomDraft) {
  const data = structuredClone({ ...fixtures })
  const calls: { path: string; method: string; body: any; signal: AbortSignal }[] = []
  let intercept: ((path: string, init: RequestInit) => Promise<Response> | undefined) | undefined
  const fetcher = (async (path: string, init: RequestInit) => {
    const route = path.replace('/api/v1', '')
    const body = init.body ? JSON.parse(String(init.body)) : undefined
    calls.push({ path: route, method: init.method ?? 'GET', body, signal: init.signal as AbortSignal })
    const held = intercept?.(route, init)
    if (held) return held
    if (route === '/sessions') {
      data.runtime = { ...data.runtime, ...body, sessionId: `s-${calls.length}`, state: 'RUNNING',
        roomId: body.mode === 'DOUYIN' ? data.snapshot.settings.roomId : 'mock-room',
        collector: { ...data.runtime.collector, mode: body.mode, state: 'CONNECTING' } }
    } else if (route.endsWith('/stop')) data.runtime = { ...data.runtime, state: 'STOPPED', collector: { ...data.runtime.collector, state: 'STOPPED' } }
    else if (route === '/config' && init.method === 'PUT') {
      if (body.expectedVersion !== data.snapshot.version) return Response.json({}, { status: 409 })
      data.snapshot = { ...data.snapshot, version: data.snapshot.version + 1, settings: body.settings }
    }
    const responses: Record<string, unknown> = {
      '/runtime': data.runtime, '/providers': data.providers, '/bootstrap': data.bootstrap, '/config': data.snapshot,
      '/runtime/diagnostics': { ...data.diagnostics, sessionId: data.runtime.sessionId, state: data.runtime.state },
      '/events/simulate': { result: 'ACCEPTED' },
      '/providers/deepseek/check': { authentication: 'PASSED', selectedModel: 'PASSED', generation: 'NOT_CHECKED', availableModels: ['mock'], code: 'OK', elapsedMs: 1 },
    }
    return Response.json(responses[route] ?? data.runtime)
  }) as typeof fetch
  const configuration = createApi(fetcher), client = createRuntimeApi(fetcher)
  const controls = createSessionControls(client, configuration, draft)
  return { data, calls, configuration, client, controls, intercept: (fn: typeof intercept) => { intercept = fn } }
}
function running(f: ReturnType<typeof fixture>, mode: CollectionMode) {
  f.data.runtime = { ...f.data.runtime, mode, state: 'RUNNING', sessionId: 'active', roomId: mode === 'MOCK' ? 'mock-room' : '123456',
    player: { ...f.data.runtime.player, ready: true }, collector: { ...f.data.runtime.collector, mode, state: 'CONNECTED' } }
}
const posts = (f: ReturnType<typeof fixture>) => f.calls.filter(call => call.method === 'POST')

test('default MOCK; saving a room, reloading config and constructing controls never connect', async t => {
  const f = fixture(); t.after(f.controls.dispose)
  await f.controls.refresh()
  await f.configuration.save(1, { ...f.data.snapshot.settings, roomId: '987654' })
  await f.controls.refresh()
  assert.equal(f.controls.saved.value?.settings.roomId, '987654')
  assert.deepEqual(f.controls.selection.value, { mode: 'MOCK', conversationMode: 'MOCK', speechMode: 'LOCAL_TEST_TONE' })
  assert.deepEqual(posts(f), [])
})
test('DOUYIN and both provider choices remain independent; start uses only the agreed fields', async t => {
  const f = fixture(); t.after(f.controls.dispose); await f.controls.refresh()
  f.controls.selection.value = { mode: 'DOUYIN', conversationMode: 'DEEPSEEK', speechMode: 'ALIYUN_NLS' }
  await f.controls.action('start')
  assert.deepEqual(posts(f).map(x => x.body), [{ mode: 'DOUYIN', conversationMode: 'DEEPSEEK', speechMode: 'ALIYUN_NLS' }])
  assert.equal(f.controls.runtime.value?.roomId, '123456')
  assert.equal(f.controls.locked.value, true)
  assert.match(f.controls.notice.value, /采集状态/)
  assert.equal(f.calls.some(x => x.method === 'PUT'), false)
  await f.controls.action('stop')
  assert.equal(f.controls.locked.value, false)
})
test('empty, dirty and uncertain room configuration block DOUYIN without saving the draft', async t => {
  const draft = reactive({ roomId: '123456', pending: false })
  const f = fixture(() => draft); t.after(f.controls.dispose); await f.controls.refresh()
  f.controls.selection.value.mode = 'DOUYIN'
  draft.roomId = '999'
  await f.controls.action('start'); assert.match(f.controls.error.value, /未保存/)
  draft.roomId = '123456'; draft.pending = true
  await f.controls.action('start'); assert.match(f.controls.error.value, /核对/)
  draft.pending = false; draft.roomId = ''; f.data.snapshot.settings.roomId = ''
  await f.controls.refresh(); await f.controls.action('start'); assert.match(f.controls.error.value, /填写并保存/)
  assert.deepEqual(posts(f), []); assert.equal(f.calls.some(x => x.method === 'PUT'), false)
  // MOCK does not need a room and cannot accidentally start Douyin.
  f.controls.selection.value.mode = 'MOCK'; await f.controls.action('start')
  assert.equal(posts(f)[0]?.body.mode, 'MOCK')
})
test('room or capability changes between polling and starting are rechecked', async t => {
  const draft = reactive({ roomId: '123456', pending: false })
  const f = fixture(() => draft); t.after(f.controls.dispose); await f.controls.refresh()
  f.controls.selection.value.mode = 'DOUYIN'; f.data.snapshot.settings.roomId = '999'
  await f.controls.action('start'); assert.match(f.controls.error.value, /未保存/)
  draft.roomId = '999'; f.data.providers.douyin.enabled = false
  await f.controls.action('start'); assert.match(f.controls.error.value, /暂未开放/)
  assert.deepEqual(posts(f), [])
})
test('availability requires explicit capabilities, permits UNVERIFIED, and flags contradictions safely', () => {
  const b = structuredClone(fixtures.bootstrap), p = structuredClone(fixtures.providers)
  assert.equal(douyinAvailability(b, p).enabled, true)
  assert.match(douyinAvailability(b, p).reason, /尚未验证/)
  b.capabilities.douyinCollector = false
  assert.equal(douyinAvailability(b, p).enabled, false)
  assert.match(douyinAvailability(b, p).reason, /不一致/)
  p.douyin.enabled = false
  assert.match(douyinAvailability(b, p).reason, /暂未开放/)
  p.douyin.reason = 'https://example.test/?private=test'
  assert.doesNotMatch(douyinAvailability(b, p).reason, /private/)
  assert.equal(douyinAvailability(null, p).enabled, false)
})
test('DOUYIN disallows all three simulation handlers, including direct calls; MOCK still allows them', async t => {
  const f = fixture(); t.after(f.controls.dispose); running(f, 'DOUYIN'); await f.controls.refresh()
  assert.equal(f.controls.selection.value.mode, 'DOUYIN'); assert.equal(f.controls.canSimulate.value, false)
  for (const action of ['COMMENT', 'GIFT', 'replay'] as const) await f.controls.action(action)
  assert.deepEqual(posts(f), [])
  running(f, 'MOCK'); await f.controls.refresh()
  assert.equal(f.controls.canSimulate.value, true)
  for (const action of ['COMMENT', 'GIFT', 'replay'] as const) await f.controls.action(action)
  assert.equal(posts(f).length, 3)
})
test('connection, reconnection, end and failure do not invent received live events', async t => {
  const f = fixture(); t.after(f.controls.dispose); running(f, 'DOUYIN')
  for (const [state, label] of [['CONNECTING', '连接中'], ['CONNECTED', '已连接'], ['RECONNECTING', '重连中'], ['ENDED', '直播已结束'], ['FAILED', '采集失败']] as const) {
    f.data.runtime.collector.state = state; await f.controls.refresh()
    const text = collectorSummary(f.controls.runtime.value!)
    assert.match(text, new RegExp(label)); assert.match(text, /尚未收到采集事件/)
  }
  f.data.runtime.collector.received = 3
  assert.match(collectorSummary(f.data.runtime), /尚无派发事件/)
  f.data.runtime.collector.emitted = 1
  assert.match(collectorSummary(f.data.runtime), /已派发 1 条事件/)
  assert.equal(safeCode('AUTH_FAILED'), 'AUTH_FAILED'); assert.equal(safeCode('private-url?token=test'), 'UNKNOWN_ERROR')
  f.data.runtime.collector.mode = 'MOCK'; await f.controls.refresh()
  assert.match(f.controls.inconsistency.value, /不一致/); assert.equal(f.controls.canSimulate.value, false)
})
test('older polling success/failure cannot overwrite a newer session or operation; transport is aborted', async t => {
  const f = fixture(); t.after(f.controls.dispose); await f.controls.refresh()
  let release!: (value: Response) => void
  f.intercept(path => path === '/runtime' ? new Promise(resolve => { release = resolve }) : undefined)
  const old = f.controls.refresh(), signal = f.calls.at(-5)!.signal
  f.intercept(undefined); running(f, 'DOUYIN'); await f.controls.refresh()
  assert.equal(signal.aborted, true)
  release(Response.json(fixtures.runtime)); await old
  assert.equal(f.controls.runtime.value?.sessionId, 'active')
  f.intercept(path => path === '/runtime' ? new Promise(resolve => { release = resolve }) : undefined)
  const oldFailure = f.controls.refresh()
  await f.controls.action('stop')
  release(Response.json({}, { status: 503 })); await oldFailure
  assert.equal(f.controls.runtime.value?.state, 'STOPPED'); assert.equal(f.controls.loadError.value, '')
})
test('load failure locks controls and recovers; failed/timed-out start is visible with no fallback/retry', async t => {
  const f = fixture(); t.after(f.controls.dispose); await f.controls.refresh(); f.controls.selection.value.mode = 'DOUYIN'
  f.intercept(path => path === '/sessions' ? Promise.reject(new RequestError('timeout', '请求超时，结果尚未确认。')) : undefined)
  await f.controls.action('start'); assert.match(f.controls.error.value, /超时/)
  assert.equal(f.controls.runtime.value, null); assert.equal(f.controls.locked.value, true); assert.equal(posts(f).length, 1)
  await f.controls.refresh(); assert.match(f.controls.error.value, /超时/); assert.equal(posts(f).length, 1)
  f.intercept(path => path === '/runtime' ? Promise.reject(new Error('private')) : undefined)
  await f.controls.refresh(); assert.match(f.controls.loadError.value, /读取失败/); assert.doesNotMatch(f.controls.loadError.value, /private/)
  f.intercept(undefined); await f.controls.refresh(); assert.equal(f.controls.loadError.value, '')
  assert.equal(f.controls.selection.value.mode, 'DOUYIN')
})
test('diagnostic session mismatch is dropped and disposal cancels all reads without publishing callbacks', async () => {
  const f = fixture(); running(f, 'DOUYIN')
  f.intercept(path => path === '/runtime/diagnostics' ? Promise.resolve(Response.json({ ...fixtures.diagnostics, sessionId: 'old' })) : undefined)
  await f.controls.refresh(); assert.equal(f.controls.diagnostics.value, null)
  let release!: (value: Response) => void
  f.intercept(path => path === '/runtime' ? new Promise(resolve => { release = resolve }) : undefined)
  const old = f.controls.refresh(); const pending = f.calls.slice(-5)
  f.controls.dispose(); assert.equal(pending.every(x => x.signal.aborted), true)
  release(Response.json(fixtures.runtime)); await old
  assert.equal(f.controls.runtime.value?.sessionId, 'active')
})
