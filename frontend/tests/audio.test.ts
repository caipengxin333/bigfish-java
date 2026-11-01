import assert from 'node:assert/strict'
import test, { beforeEach, afterEach } from 'node:test'
import { BrowserAudio } from '../src/stage/audio.ts'
import type { AudioPort } from '../src/stage/controller.ts'

class FakeSource {
  buffer: unknown; onended: (() => void) | null = null; started = false; stopped = false
  connect() {} disconnect() {}
  start() { this.started = true }
  stop() { this.stopped = true }
}
class FakeContext {
  static latest: FakeContext
  state = 'suspended'; currentTime = 0; destination = {}; onstatechange: (() => void) | null = null
  source: FakeSource | null = null
  constructor() { FakeContext.latest = this }
  async resume() { this.state = 'running' }
  async close() { this.state = 'closed' }
  async decodeAudioData(_bytes: ArrayBuffer) { return { duration: 4 } }
  createBufferSource() { return this.source = new FakeSource() }
  createGain() { return { gain: { value: 1 }, connect() {}, disconnect() {} } }
  createAnalyser() { return { fftSize: 256, connect() {}, disconnect() {}, getFloatTimeDomainData(data: Float32Array) { data.fill(.1) } } }
}
let frames = new Map<number, FrameRequestCallback>(), nextFrame = 1
let saved: Record<string, PropertyDescriptor | undefined>
beforeEach(() => {
  saved = Object.fromEntries(['AudioContext', 'requestAnimationFrame', 'cancelAnimationFrame'].map(k => [k, Object.getOwnPropertyDescriptor(globalThis, k)]))
  Object.defineProperty(globalThis, 'AudioContext', { configurable: true, value: FakeContext })
  Object.defineProperty(globalThis, 'requestAnimationFrame', { configurable: true, value: (cb: FrameRequestCallback) => { const id = nextFrame++; frames.set(id, cb); return id } })
  Object.defineProperty(globalThis, 'cancelAnimationFrame', { configurable: true, value: (id: number) => frames.delete(id) })
})
afterEach(() => {
  for (const [key, descriptor] of Object.entries(saved)) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else Reflect.deleteProperty(globalThis, key) }
  frames.clear()
})
const flush = () => new Promise(resolve => setImmediate(resolve))
const frame = () => { const batch = [...frames.values()]; frames.clear(); batch.forEach(cb => cb(0)) }
const task = { taskId: 'audio', sessionId: 'session', text: 'test', audioUrl: '/api/v1/audio/test', expiresAt: Date.now() + 60000 }
function callbacks() {
  const events: string[] = []
  const hooks: Parameters<AudioPort['play']>[1] = { started: () => events.push('STARTED'), ended: () => events.push('COMPLETED'), failed: code => events.push(code), level: level => { if (level > 0) events.push('LEVEL') } }
  return { events, hooks }
}
test('no AudioContext without click; STARTED waits for advancing audio clock; COMPLETED waits for onended', async () => {
  const player = new BrowserAudio(async () => new ArrayBuffer(44)), { events, hooks } = callbacks()
  player.play(task, hooks); assert.deepEqual(events, ['PLAYBACK_BLOCKED']); events.length = 0
  await player.enable(); player.play(task, hooks); await flush()
  const context = FakeContext.latest
  assert.equal(context.source?.started, true); assert.deepEqual(events, [])
  frame(); assert.deepEqual(events, [])
  context.currentTime = .05; frame(); assert.deepEqual(events, ['STARTED', 'LEVEL'])
  context.source!.onended!(); assert.equal(events.at(-1), 'COMPLETED'); assert.equal(context.source!.stopped, true)
  player.dispose()
})
test('cancel while downloading ignores late bytes and never creates a source', async () => {
  let finish!: (value: ArrayBuffer) => void
  const player = new BrowserAudio(() => new Promise(resolve => { finish = resolve })), { events, hooks } = callbacks()
  await player.enable(); player.play(task, hooks); player.stop(); finish(new ArrayBuffer(44)); await flush()
  assert.equal(FakeContext.latest.source, null); assert.deepEqual(events, []); player.dispose()
})
test('decode failure, download failure and context interruption use stable errors', async () => {
  const { events, hooks } = callbacks()
  const download = new BrowserAudio(async () => { throw new Error('internal download error') })
  await download.enable(); download.play(task, hooks); await flush(); assert.deepEqual(events, ['AUDIO_DOWNLOAD_FAILED']); download.dispose()
  events.length = 0
  const player = new BrowserAudio(async () => new ArrayBuffer(44))
  await player.enable(); FakeContext.latest.decodeAudioData = async () => { throw new Error('bad audio') }
  player.play(task, hooks); await flush(); assert.deepEqual(events, ['AUDIO_DECODE_FAILED']); player.dispose()
  events.length = 0
  const interrupted = new BrowserAudio(async () => new ArrayBuffer(44))
  await interrupted.enable(); interrupted.play(task, hooks); await flush()
  FakeContext.latest.state = 'suspended'; FakeContext.latest.onstatechange!()
  assert.deepEqual(events, ['AUDIO_INTERRUPTED']); assert.equal(FakeContext.latest.source!.stopped, true); interrupted.dispose()
})
test('stop disconnects source and cancels animation; old onended cannot complete a cancelled task', async () => {
  const player = new BrowserAudio(async () => new ArrayBuffer(44)), { events, hooks } = callbacks()
  await player.enable(); player.play(task, hooks); await flush()
  FakeContext.latest.currentTime = 1; frame(); const oldEnd = FakeContext.latest.source!.onended!
  player.stop(); oldEnd(); frame()
  assert.deepEqual(events, ['STARTED', 'LEVEL']); assert.equal(FakeContext.latest.source!.stopped, true); assert.equal(frames.size, 0); player.dispose()
})


test('STARTED uses the audio clock timer when animation frames are paused in the background', async () => {
  const player = new BrowserAudio(async () => new ArrayBuffer(44)), { events, hooks } = callbacks()
  await player.enable(); player.play(task, hooks); await flush()
  FakeContext.latest.currentTime = 0.1
  // Never call frame(): a hidden tab may not receive requestAnimationFrame.
  await new Promise(resolve => setTimeout(resolve, 80))
  assert.deepEqual(events, ['STARTED'])
  FakeContext.latest.source!.onended!()
  assert.deepEqual(events, ['STARTED', 'COMPLETED']); player.dispose()
})
