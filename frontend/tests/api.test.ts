import assert from 'node:assert/strict'
import test from 'node:test'
import { createApi, RequestError, isSnapshot, copySettings } from '../src/api.ts'
const settings = { profileName: 'test', persona: '', roomId: '', model: 'mock', maxOutputTokens: 128, voice: 'mock', readComment: true, welcomeIntervalSeconds: 30, synthesisQueueCapacity: 8, playbackQueueCapacity: 8, priorities: { gift: 60, comment: 50, follow: 10, entrance: 5 } }
const snapshot = { version: 1, updatedAt: new Date().toISOString(), settings }
const kind = (value: string) => (e: unknown) => e instanceof RequestError && e.kind === value
const fetcher = (fn: (...args: any[]) => any) => fn as typeof fetch

test('config GET validates response and PUT sends optimistic version with published fields', async () => {
  let sent: any
  const api = createApi(fetcher(async (_url, init) => { if (init.body) sent = JSON.parse(init.body); return Response.json(snapshot) }))
  assert.deepEqual(await api.config(), snapshot)
  await api.save(1, { ...settings, unexpected: 'ignored' } as typeof settings)
  assert.deepEqual(sent, { expectedVersion: 1, settings }); assert.equal('unexpected' in copySettings(sent.settings), false)
})
test('409 preserves actionable conflict type without echoing arbitrary response text', async () => {
  const api = createApi(fetcher(async () => Response.json({ message: 'untrusted server text' }, { status: 409 })))
  await assert.rejects(api.save(1, settings), e => kind('conflict')(e) && !(e as Error).message.includes('untrusted'))
})
test('network errors and timeouts have distinct hints', async () => {
  await assert.rejects(createApi(fetcher(async () => { throw new TypeError('network') })).config(), kind('network'))
  const api = createApi(fetcher((_url, init) => new Promise((_, reject) => init.signal.addEventListener('abort', () => reject(new Error('aborted'))))), 5)
  await assert.rejects(api.config(), kind('timeout'))
})
test('invalid JSON, schema and HTTP errors do not silently become success', async () => {
  for (const response of [new Response('<html>'), Response.json({ version: 1 }), Response.json({ ...snapshot, settings: { ...settings, readComment: 'yes' } })]) await assert.rejects(createApi(fetcher(async () => response)).config(), kind('invalid'))
  await assert.rejects(createApi(fetcher(async () => new Response('', { status: 503 }))).bootstrap(), kind('http'))
  assert.equal(isSnapshot(snapshot), true)
  assert.equal(isSnapshot({ ...snapshot, version: 1.5 }), false)
})
