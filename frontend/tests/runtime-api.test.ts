import assert from 'node:assert/strict'
import test from 'node:test'
import { createRuntimeApi, isRuntime } from '../src/runtime-api.ts'
import { RequestError } from '../src/api.ts'
const queue = { depth: 0, capacity: 8 }
const runtime = { sessionId: 's', state: 'RUNNING', mode: 'MOCK', conversationMode: 'MOCK', speechMode: 'LOCAL_TEST_TONE', roomId: 'mock-room',
  savedConfigVersion: 1, appliedConfigVersion: 1, queues: { comments: queue, templates: queue, synthesis: queue, playback: queue, modelActive: false, speechActive: false },
  player: { connected: true, ready: true, playing: false, leaseGeneration: 1 },
  collector: { mode: 'MOCK', state: 'CONNECTED', received: 0, emitted: 0, duplicates: 0, rejected: 0, lastError: '' }, dropped: 0, failures: 0 }
const fetcher = (fn: (...args: any[]) => any) => fn as typeof fetch

test('runtime validates all displayed queue/player/collector fields', () => {
  assert.equal(isRuntime(runtime), true)
  assert.equal(isRuntime({ ...runtime, queues: {} }), false)
  assert.equal(isRuntime({ ...runtime, player: { ...runtime.player, ready: 'true' } }), false)
  assert.equal(isRuntime({ ...runtime, speechMode: 'REAL' }), false)
})
test('start explicitly selects mock and stop/replay carry the session identity', async () => {
  const calls: any[] = []
  const api = createRuntimeApi(fetcher(async (url, init) => { calls.push([url, JSON.parse(init.body)]); return Response.json(runtime) }))
  await api.start(); await api.stop('s'); await api.replay('s')
  assert.deepEqual(calls, [['/api/v1/sessions', { mode: 'MOCK', conversationMode: 'MOCK' }], ['/api/v1/sessions/s/stop', {}], ['/api/v1/sessions/s/replay', {}]])
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
