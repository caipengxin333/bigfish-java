import assert from 'node:assert/strict'
import test from 'node:test'
import { createRuntimeApi, isDiagnostics, isModelCheck, isProviders, isRuntime, isTokenState } from '../src/runtime-api.ts'
import { RequestError } from '../src/api.ts'
const queue = { depth: 0, capacity: 8 }
const runtime = { sessionId: 's', state: 'RUNNING', mode: 'MOCK', conversationMode: 'MOCK', speechMode: 'LOCAL_TEST_TONE', roomId: 'mock-room',
  savedConfigVersion: 1, appliedConfigVersion: 1, queues: { comments: queue, templates: queue, synthesis: queue, playback: queue, modelActive: false, speechActive: false },
  player: { connected: true, ready: true, playing: false, leaseGeneration: 1 },
  collector: { mode: 'MOCK', state: 'CONNECTED', received: 0, emitted: 0, duplicates: 0, rejected: 0, lastError: '' }, dropped: 0, failures: 0, lastError: '', lastErrorAt: '' }
const fetcher = (fn: (...args: any[]) => any) => fn as typeof fetch
const token = { mode: 'STATIC', status: 'NOT_REQUESTED', expiresAt: null, remainingSeconds: null, refreshResult: 'NOT_REQUESTED', lastError: '', lastAttemptAt: null, lastSuccessAt: null, retryAfterMillis: 0, attempts: 0 }

test('runtime validates all displayed queue/player/collector fields', () => {
  assert.equal(isRuntime(runtime), true)
  assert.equal(isRuntime({ ...runtime, queues: {} }), false)
  assert.equal(isRuntime({ ...runtime, player: { ...runtime.player, ready: 'true' } }), false)
  assert.equal(isRuntime({ ...runtime, speechMode: 'REAL' }), false)
  assert.equal(isRuntime({ ...runtime, speechMode: 'ALIYUN_NLS', lastError: 'NLS_READ_TIMEOUT' }), true)
  assert.equal(isRuntime({ ...runtime, lastError: undefined }), false)
})
test('start explicitly selects mock and stop/replay carry the session identity', async () => {
  const calls: any[] = []
  const api = createRuntimeApi(fetcher(async (url, init) => { calls.push([url, JSON.parse(init.body)]); return Response.json(runtime) }))
  await api.start(); await api.stop('s'); await api.replay('s')
  assert.deepEqual(calls, [['/api/v1/sessions', { mode: 'MOCK', conversationMode: 'MOCK', speechMode: 'LOCAL_TEST_TONE' }], ['/api/v1/sessions/s/stop', {}], ['/api/v1/sessions/s/replay', {}]])
})
test('real providers are explicit and independent of mock event collection', async () => {
  const api = createRuntimeApi(fetcher(async (_url, init) => {
    assert.deepEqual(JSON.parse(init.body), { mode: 'MOCK', conversationMode: 'DEEPSEEK', speechMode: 'ALIYUN_NLS' })
    return Response.json({ ...runtime, conversationMode: 'DEEPSEEK', speechMode: 'ALIYUN_NLS' })
  }))
  assert.equal((await api.start('DEEPSEEK', 'ALIYUN_NLS')).speechMode, 'ALIYUN_NLS')
})
test('provider status and explicit catalog check validate different contracts', async () => {
  const p = { configured: false, enabled: false, state: 'MISSING', reason: 'MISSING_CREDENTIALS' }
  const providers = { deepseek: p, aliyun: p, douyin: p, aliyunVoices: ['xiaoyun'], speechTextLimit: 300, audioDurationLimitSeconds: 25, aliyunToken: token }
  const check = { authentication: 'PASSED', selectedModel: 'FAILED', generation: 'NOT_CHECKED', availableModels: ['test-model'], code: 'MODEL_NOT_FOUND', elapsedMs: 100 }
  assert.equal(isProviders(providers), true); assert.equal(isProviders({ ...providers, aliyun: { ...p, enabled: 'true' } }), false)
  assert.equal(isModelCheck(check), true); assert.equal(isModelCheck({ ...check, generation: 'READY' }), false)
  const calls: any[] = []
  const api = createRuntimeApi(fetcher(async (url, init) => { calls.push([url, init.method]); return Response.json(String(url).endsWith('/check') ? check : providers) }))
  await api.providers(); await api.checkModel()
  assert.deepEqual(calls, [['/api/v1/providers', 'GET'], ['/api/v1/providers/deepseek/check', 'POST']])
})
test('token expiry is explicit and bounded diagnostics use a read-only endpoint', async () => {
  assert.equal(isTokenState(token), true)
  assert.equal(isTokenState({ ...token, mode: 'RAM', remainingSeconds: 120, expiresAt: '2026-11-01T12:00:00Z' }), true)
  assert.equal(isTokenState({ ...token, remainingSeconds: -1 }), false)
  assert.equal(isTokenState({ ...token, expiresAt: 'invalid' }), false)
  const worker = { threads: 1, active: 0, queued: 0, completed: 0 }
  const trace = { sequence: 1, taskId: 'task', eventType: 'GIFT', configVersion: 1, stage: 'SKIPPED', reason: 'GIFT_MERGED', at: '2026-11-01T11:00:00Z', previousStageMs: 0, taskElapsedMs: 0 }
  const diagnostics = { sessionId: 's', state: 'RUNNING', capturedAt: trace.at, waiting: ['PLAYER_NOT_READY'], lastReceivedAt: trace.at,
    lastModelSuccessAt: null, lastSpeechSuccessAt: null, lastPlaybackSuccessAt: null, lastFailure: null, recent: [trace],
    taskEvictions: 0, historyEvictions: 0, audioAssets: 0, dedupEntries: 1, modelWorker: worker, speechWorker: worker }
  assert.equal(isDiagnostics(diagnostics), true)
  assert.equal(isDiagnostics({ ...diagnostics, recent: Array(21).fill(trace) }), false)
  assert.equal(isDiagnostics({ ...diagnostics, lastFailure: { ...trace, taskElapsedMs: -1 } }), false)
  assert.equal(isDiagnostics({ ...diagnostics, modelWorker: { ...worker, active: 'yes' } }), false)
  const api = createRuntimeApi(fetcher(async (url, init) => {
    assert.equal(url, '/api/v1/runtime/diagnostics'); assert.equal(init.method, 'GET'); assert.equal(init.body, undefined)
    return Response.json(diagnostics)
  }))
  assert.equal((await api.diagnostics()).sessionId, 's')
})
test('event rejection is visible and does not masquerade as success', async () => {
  const api = createRuntimeApi(fetcher(async (_url, init) => {
    const event = JSON.parse(init.body)
    assert.equal(event.sessionId, 's'); assert.equal(event.payload.quantity, 1); assert.equal(event.payload.unitValue, null)
    return Response.json({ result: 'REJECTED_FULL' }, { status: 429 })
  }))
  assert.equal((await api.simulate('s', 'GIFT')).result, 'REJECTED_FULL')
})
test('session conflict differs from config conflict and error payload is not reflected', async () => {
  const api = createRuntimeApi(fetcher(async () => Response.json({ code: 'SESSION_ACTIVE', message: 'secret' }, { status: 409 })))
  await assert.rejects(api.start(), e => e instanceof RequestError && e.message.includes('已有运行会话') && !e.message.includes('secret'))
  await assert.rejects(createRuntimeApi(fetcher(async () => Response.json({}))).status(), e => e instanceof RequestError && e.kind === 'invalid')
})
test('timed out start is never retried automatically', async () => {
  let calls = 0
  const api = createRuntimeApi(fetcher((_url, init) => { calls++; return new Promise((_, reject) => init.signal.addEventListener('abort', () => reject(new Error()))) }), 5)
  await assert.rejects(api.start(), e => e instanceof RequestError && e.kind === 'timeout')
  assert.equal(calls, 1)
})
