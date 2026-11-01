// Explicit local-only smoke check. Saves and restores the display name; never contacts AI/Douyin.
import assert from 'node:assert/strict'
const base = process.env.BIGFISH_BASE_URL || 'http://127.0.0.1:18080'
const target = new URL(base)
assert.equal(target.hostname, '127.0.0.1', 'Smoke test only accepts localhost')
async function json(path, init) {
  const response = await fetch(base + path, { ...init, signal: AbortSignal.timeout(5000) })
  return { status: response.status, body: await response.json() }
}
const health = await json('/actuator/health')
assert.equal(health.status, 200); assert.equal(health.body.status, 'UP')
const status = await json('/api/v1/bootstrap')
assert.equal(status.body.mode, 'READY')
const providers = await json('/api/v1/providers')
assert.equal(providers.status, 200)
assert.equal(status.body.capabilities.douyinCollector, true)
assert.equal(providers.body.douyin.configured, true)
assert.equal(providers.body.douyin.enabled, true)
assert.equal(providers.body.douyin.state, 'UNVERIFIED')
assert.equal(providers.body.douyin.reason, 'LIVE_RECEIVE_NOT_VERIFIED')
assert.ok(['STATIC', 'RAM', 'INVALID'].includes(providers.body.aliyunToken.mode))
const diagnostics = await json('/api/v1/runtime/diagnostics')
assert.equal(diagnostics.status, 200)
assert.ok(Array.isArray(diagnostics.body.waiting))
assert.ok(Array.isArray(diagnostics.body.recent) && diagnostics.body.recent.length <= 20)
assert.equal(status.body.capabilities.speech, providers.body.aliyun.enabled)
for (const path of ['/admin', '/stage']) {
  const page = await fetch(base + path)
  assert.equal(page.status, 200)
  assert.match(await page.text(), /id="app"/)
}
const original = (await json('/api/v1/config')).body
const payload = { expectedVersion: original.version, settings: { ...original.settings, profileName: 'Smoke check' } }
let saved
try {
  saved = await json('/api/v1/config', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
  assert.equal(saved.status, 200)
  assert.equal(saved.body.version, original.version + 1)
  const read = await json('/api/v1/config')
  assert.equal(read.body.settings.profileName, 'Smoke check')
  const conflict = await json('/api/v1/config', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
  assert.equal(conflict.status, 409)
} finally {
  if (saved?.status === 200) {
    const restored = await json('/api/v1/config', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ expectedVersion: saved.body.version, settings: original.settings }) })
    assert.equal(restored.status, 200, 'Could not restore original settings; another writer may have updated them')
  }
}
console.log('PASS: health, enabled-but-unverified Douyin capability (no network probe), provider/token state, bounded diagnostics, bundled admin/stage pages, persisted configuration and stale-version rejection. Original settings restored.')
