import assert from 'node:assert/strict'
import test from 'node:test'
import { avatarStorageKey, defaultAvatarSettings, parseAvatarSettings, serializeAvatarSettings } from '../src/stage/avatar-settings.ts'
import { AvatarError, assetPath, avatarErrorMessage, fetchAsset, modelUrl, parseManifest } from '../src/stage/live2d/assets.ts'
import { AvatarLifetime, MouthEnvelope, RenderLoop, mappedParameters } from '../src/stage/live2d/lifecycle.ts'
import type { AvatarRenderer } from '../src/stage/live2d/lifecycle.ts'
import { trackGraphicsResources } from '../src/stage/live2d/gl-resources.ts'
const bytes = (value: unknown) => new TextEncoder().encode(JSON.stringify(value)).buffer
const manifest = { Version: 3, FileReferences: { Moc: 'fish.moc3', Textures: ['tex/texture.png'], Physics: 'fish.physics3.json', Expressions: [{ Name: '微笑', File: 'smile.exp3.json' }, { Name: '13水印消除', File: 'hide.exp3.json' }] }, Groups: [{ Target: 'Parameter', Name: 'LipSync', Ids: ['CustomMouthA', 'CustomMouthB'] }, { Target: 'Parameter', Name: 'EyeBlink', Ids: ['CustomEye'] }] }
test('avatar settings default to SVG and only persist versioned allowed browser preferences', () => {
  assert.equal(avatarStorageKey, 'bigfish.browser.avatar.v1')
  for (const raw of [null, '{}', '{broken', JSON.stringify({ version: 2, mode: 'live2d' })]) assert.deepEqual(parseAvatarSettings(raw), defaultAvatarSettings)
  const valid = { mode: 'live2d', size: 1.1, x: -20, y: 30, background: 'dark' } as const
  assert.deepEqual(parseAvatarSettings(serializeAvatarSettings(valid)), valid)
  const encoded = serializeAvatarSettings({ ...valid, token: 'fake-sensitive', modelUrl: 'https://remote.invalid/model' } as typeof valid)
  assert.ok(!encoded.includes('token') && !encoded.includes('modelUrl'))
  assert.deepEqual(parseAvatarSettings(JSON.stringify({ version: 1, mode: 'unknown', size: 999, x: '1', y: -200, background: 'url(evil)' })), defaultAvatarSettings)
})
test('manifest maps custom lip/blink names and forbids removal of attribution expressions', () => {
  const value = parseManifest(bytes(manifest))
  assert.deepEqual(value.lips, ['CustomMouthA', 'CustomMouthB']); assert.deepEqual(value.eyes, ['CustomEye'])
  assert.equal(value.expressions[0].restricted, false); assert.equal(value.expressions[1].restricted, true)
  assert.deepEqual(mappedParameters(value.lips, new Map([['CustomMouthA', 10], ['ParamMouthOpenY', 99]])), [10])
  assert.deepEqual(mappedParameters([], new Map([['ParamMouthOpenY', 99]])), [])
})
test('asset paths cannot leave the fixed same-origin model folder', () => {
  assert.equal(assetPath('大肥鱼.8192/texture.png'), '/avatar-assets/models/BigFish/%E5%A4%A7%E8%82%A5%E9%B1%BC.8192/texture.png')
  for (const path of ['//evil/model', 'https://evil/model', '../model', 'a/../b', '%2e%2e/a', 'a\\b', '/root', 'file?token=x', 'a#x', '', 'a//b']) assert.throws(() => assetPath(path), AvatarError)
})
test('malformed, too many assets or incompatible model configs are rejected safely', () => {
  for (const value of [{}, { ...manifest, Version: 2 }, { ...manifest, FileReferences: { ...manifest.FileReferences, Moc: '../bad' } }, { ...manifest, FileReferences: { ...manifest.FileReferences, Textures: Array(5).fill('x') } }, { ...manifest, Groups: [{ Target: 'Parameter', Name: 'LipSync', Ids: ['<unsafe>'] }] }]) assert.throws(() => parseManifest(bytes(value)), /MODEL_INVALID/)
  assert.throws(() => parseManifest(new ArrayBuffer(2)), /MODEL_INVALID/)
  assert.ok(!avatarErrorMessage(new Error('secret-token=http://unsafe')).includes('secret'))
  assert.match(avatarErrorMessage(new AvatarError('WEBGL_UNAVAILABLE')), /WebGL.*占位.*声音/)
})
test('asset fetch bounds bytes, refuses redirects, handles missing files and aborts', async () => {
  const original = globalThis.fetch, signal = new AbortController()
  try {
    globalThis.fetch = async (_url, options) => { assert.equal(options?.redirect, 'error'); return new Response(new Uint8Array([1, 2, 3])) }
    assert.equal((await fetchAsset(modelUrl, signal.signal, 3)).byteLength, 3)
    await assert.rejects(fetchAsset(modelUrl, signal.signal, 2), /ASSET_TOO_LARGE/)
    globalThis.fetch = async () => new Response('', { status: 404 })
    await assert.rejects(fetchAsset(modelUrl, signal.signal, 3), /ASSET_MISSING/)
    globalThis.fetch = async () => { throw new Error('network detail is not exposed') }
    await assert.rejects(fetchAsset(modelUrl, signal.signal, 3), /ASSET_FAILED/)
    signal.abort(); await assert.rejects(fetchAsset(modelUrl, signal.signal, 3), /CANCELLED/)
    await assert.rejects(fetchAsset('https://remote.invalid', signal.signal, 3), /MODEL_INVALID/)
  } finally { globalThis.fetch = original }
})
test('mouth follows volume only while speaking with a lease; cancellation closes without a frame', () => {
  const mouth = new MouthEnvelope(), active = { mouthLevel: .9, speaking: true, connected: true }
  assert.ok(mouth.update(active, .05) > 0 && mouth.level < .9)
  assert.equal(mouth.update({ ...active, speaking: false }, 0), 0)
  mouth.update(active, .05); assert.equal(mouth.update({ ...active, connected: false }, 0), 0)
  mouth.update(active, .05); assert.equal(mouth.update({ ...active, mouthLevel: 0 }, 0), 0)
  mouth.update(active, .05); assert.equal(mouth.update({ ...active, mouthLevel: NaN }, 0), 0)
  assert.ok(mouth.update({ ...active, mouthLevel: 5 }, 1) <= 1)
})
function fakeRenderer(dispose: () => void): AvatarRenderer { return { expressions: [], warnings: [], update() {}, async expression() {}, dispose } }
test('unmount cancels loading; a late successful renderer is immediately released', async () => {
  const owner = new AvatarLifetime(); let finish!: (value: AvatarRenderer) => void, signal!: AbortSignal, releases = 0
  const pending = owner.load(s => { signal = s; return new Promise(resolve => { finish = resolve }) })
  owner.dispose(); assert.ok(signal.aborted)
  finish(fakeRenderer(() => releases++)); assert.equal(await pending, undefined); assert.equal(releases, 1)
})
test('replacing an avatar releases the old renderer; failure propagates for fallback and can retry', async () => {
  const owner = new AvatarLifetime(); let releases = 0
  await owner.load(async () => fakeRenderer(() => releases++))
  await assert.rejects(owner.load(async () => { throw new AvatarError('ASSET_MISSING') }), /ASSET_MISSING/)
  assert.equal(releases, 1)
  await owner.load(async () => fakeRenderer(() => releases++)); owner.dispose(); owner.dispose(); assert.equal(releases, 2)
})
test('render loop cancels frames, ignores old callbacks and bounds time after sleeping', () => {
  const request = globalThis.requestAnimationFrame, cancel = globalThis.cancelAnimationFrame, callbacks = new Map<number, FrameRequestCallback>()
  let id = 0; const steps: number[] = [], errors: unknown[] = []
  globalThis.requestAnimationFrame = cb => { callbacks.set(++id, cb); return id }; globalThis.cancelAnimationFrame = key => { callbacks.delete(key) }
  try {
    const loop = new RenderLoop(dt => steps.push(dt), e => errors.push(e))
    callbacks.get(1)!(100); callbacks.get(2)!(100000)
    assert.deepEqual(steps, [0, .05]); const old = callbacks.get(3)!; loop.stop(); old(100010); assert.equal(steps.length, 2)
    const failing = new RenderLoop(() => { throw new Error('render') }, e => errors.push(e))
    const last = callbacks.get(id)!; last(200000); last(200010); assert.equal(errors.length, 1); failing.stop()
  } finally { globalThis.requestAnimationFrame = request; globalThis.cancelAnimationFrame = cancel }
})
test('private WebGL allocations, including SDK mask textures and framebuffers, are deleted exactly once', () => {
  const textures: unknown[] = [], buffers: unknown[] = []
  const gl = { createTexture: () => ({}), deleteTexture: (value: unknown) => { textures.push(value) }, createFramebuffer: () => ({}), deleteFramebuffer: (value: unknown) => { buffers.push(value) } } as unknown as WebGLRenderingContext
  const original = gl.createTexture, release = trackGraphicsResources(gl)
  const model = gl.createTexture(), mask = gl.createTexture(), framebuffer = gl.createFramebuffer()
  gl.deleteTexture(model) // Already released by the caller/SDK.
  release(); release()
  assert.deepEqual(textures, [model, mask]); assert.deepEqual(buffers, [framebuffer]); assert.equal(gl.createTexture, original)
})
test('resource timeout has a distinct safe message and does not leak original failure details', async () => {
  const originalFetch = globalThis.fetch, originalTimeout = AbortSignal.timeout
  try {
    AbortSignal.timeout = () => AbortSignal.abort(new DOMException('timed out', 'TimeoutError'))
    globalThis.fetch = async (_url, options) => { options?.signal?.throwIfAborted(); throw new Error('unreachable') }
    await assert.rejects(fetchAsset(modelUrl, new AbortController().signal, 3), /ASSET_TIMEOUT/)
    assert.match(avatarErrorMessage(new AvatarError('ASSET_TIMEOUT')), /超时/)
  } finally { globalThis.fetch = originalFetch; AbortSignal.timeout = originalTimeout }
})
