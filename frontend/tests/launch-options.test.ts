import assert from 'node:assert/strict'
import test from 'node:test'
import { createLaunchOptions, launchOptionsKey, defaultSelection } from '../src/launch-options.ts'

test('namespaced versioned options start with MOCK and persist only three whitelisted enums', () => {
  const values = new Map<string, string>(), store = { getItem: (key: string) => values.get(key) ?? null, setItem: (key: string, value: string) => { values.set(key, value) } }
  const a = createLaunchOptions(store), b = createLaunchOptions(store)
  a.reload(); assert.deepEqual(a.options.value, defaultSelection())
  assert.equal(a.save({ mode: 'DOUYIN', conversationMode: 'DEEPSEEK', speechMode: 'ALIYUN_NLS', roomId: '123', privateData: 'not persisted' }), true)
  assert.deepEqual(JSON.parse(values.get(launchOptionsKey)!), { version: 1, options: { mode: 'DOUYIN', conversationMode: 'DEEPSEEK', speechMode: 'ALIYUN_NLS' } })
  b.onStorage({ key: launchOptionsKey }); assert.deepEqual(b.options.value, a.options.value)
  assert.equal(a.save({ ...defaultSelection(), mode: 'AUTO' }), false)
  assert.equal(a.save({ ...defaultSelection(), mode: ['MOCK'] }), false)
  assert.equal(a.options.value.mode, 'DOUYIN')
  values.set(launchOptionsKey, JSON.stringify({ version: 2, options: a.options.value }))
  b.onStorage({ key: launchOptionsKey }); assert.deepEqual(b.options.value, defaultSelection()); assert.match(b.error.value, /无法读取/)
  values.clear(); b.onStorage({ key: null }); assert.equal(b.error.value, '')
})
test('storage failure is explicit and cannot silently claim options synced to another page', () => {
  const options = createLaunchOptions({ getItem: () => { throw new Error() }, setItem: () => { throw new Error() } })
  options.reload(); assert.deepEqual(options.options.value, defaultSelection()); assert.match(options.error.value, /无法读取/)
  assert.equal(options.save({ ...defaultSelection(), mode: 'DOUYIN' }), false)
  assert.match(options.error.value, /尚未同步/); assert.equal(options.options.value.mode, 'MOCK')
})
