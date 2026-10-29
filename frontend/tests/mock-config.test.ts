import assert from 'node:assert/strict'
import test from 'node:test'
import { mockConfig } from '../src/mock/config.ts'
import { RequestError } from '../src/api.ts'

test('mock saves serialize CAS across concurrent writers and reject unsupported locking', async () => {
  const oldStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  const oldNavigator = Object.getOwnPropertyDescriptor(globalThis, 'navigator')
  let stored: string | null = null, queue = Promise.resolve()
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: () => stored, setItem: (_key: string, value: string) => { stored = value } } })
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { locks: {
    request: (_name: string, work: () => Promise<unknown>) => { const result = queue.then(work); queue = result.then(() => {}, () => {}); return result },
  } } })
  try {
    const initial = await mockConfig.config()
    const results = await Promise.allSettled([mockConfig.save(1, initial.settings), mockConfig.save(1, initial.settings)])
    assert.equal(results.filter(x => x.status === 'fulfilled').length, 1)
    const failure = results.find(x => x.status === 'rejected') as PromiseRejectedResult
    assert.ok(failure.reason instanceof RequestError); assert.equal(failure.reason.kind, 'conflict')
    assert.equal((await mockConfig.config()).version, 2)
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {} })
    await assert.rejects(mockConfig.save(2, initial.settings), e => e instanceof RequestError && e.kind === 'invalid')
  } finally {
    if (oldStorage) Object.defineProperty(globalThis, 'localStorage', oldStorage); else Reflect.deleteProperty(globalThis, 'localStorage')
    if (oldNavigator) Object.defineProperty(globalThis, 'navigator', oldNavigator); else Reflect.deleteProperty(globalThis, 'navigator')
  }
})
