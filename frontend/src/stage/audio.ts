import type { AudioPort } from './controller.ts'
import type { StageTask } from './protocol.ts'
export type AudioLoader = (url: string, signal: AbortSignal) => Promise<ArrayBuffer>
export const downloadAudio: AudioLoader = async (url, signal) => {
  const response = await fetch(url, { signal, credentials: 'same-origin', redirect: 'error' })
  if (!response.ok || !response.body) throw new Error('AUDIO_DOWNLOAD_FAILED')
  const limit = 20 * 1024 * 1024
  if (Number(response.headers.get('Content-Length')) > limit) { await response.body.cancel(); throw new Error('AUDIO_TOO_LARGE') }
  const reader = response.body.getReader(), chunks: Uint8Array[] = []
  let length = 0
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      length += value.byteLength
      if (length > limit) { await reader.cancel(); throw new Error('AUDIO_TOO_LARGE') }
      chunks.push(value)
    }
  } finally { reader.releaseLock() }
  const result = new Uint8Array(length)
  let offset = 0
  for (const chunk of chunks) { result.set(chunk, offset); offset += chunk.byteLength }
  return result.buffer
}
export class BrowserAudio implements AudioPort {
  private context: AudioContext | null = null
  private source: AudioBufferSourceNode | null = null
  private analyser: AnalyserNode | null = null
  private gain: GainNode | null = null
  private abort: AbortController | null = null
  private frame = 0
  private timer: ReturnType<typeof setTimeout> | null = null
  private startMonitor: ReturnType<typeof setInterval> | null = null
  private sequence = 0
  private loader: AudioLoader
  constructor(loader: AudioLoader = downloadAudio) { this.loader = loader }
  async enable() {
    // Called directly from the user click. No context is created by the admin page.
    this.context ??= new AudioContext()
    await this.context.resume()
    if (this.context.state !== 'running') throw new Error('PLAYBACK_BLOCKED')
  }
  isEnabled() { return this.context?.state === 'running' }
  play(task: StageTask, callbacks: Parameters<AudioPort['play']>[1]) {
    this.stop()
    const context = this.context, sequence = this.sequence
    if (!context || context.state !== 'running') { callbacks.failed('PLAYBACK_BLOCKED'); return }
    const abort = new AbortController(); this.abort = abort
    const active = () => this.sequence === sequence && !abort.signal.aborted
    const fail = (code: string) => { if (active()) { this.stop(); callbacks.failed(code) } }
    // Covers download AND decode, even though decodeAudioData itself cannot be aborted.
    this.timer = setTimeout(() => fail('AUDIO_DOWNLOAD_TIMEOUT'), 12000)
    void (async () => {
      let bytes: ArrayBuffer
      try { bytes = await this.loader(task.audioUrl, abort.signal) }
      catch (e) { fail(e instanceof Error && e.message === 'AUDIO_TOO_LARGE' ? e.message : 'AUDIO_DOWNLOAD_FAILED'); return }
      if (!active()) return
      let buffer: AudioBuffer
      try { buffer = await context.decodeAudioData(bytes) }
      catch { fail('AUDIO_DECODE_FAILED'); return }
      if (!active()) return
      if (buffer.duration <= 0 || buffer.duration > 120) { fail('AUDIO_TOO_LONG'); return }
      if (context.state !== 'running') { fail('PLAYBACK_BLOCKED'); return }
      if (Date.now() >= task.expiresAt) { fail('TASK_EXPIRED'); return }
      if (this.timer) clearTimeout(this.timer)
      const source = context.createBufferSource(), analyser = context.createAnalyser(), gain = context.createGain()
      this.source = source; this.analyser = analyser; this.gain = gain
      gain.gain.value = 0.6; analyser.fftSize = 256
      source.buffer = buffer; source.connect(gain); gain.connect(analyser); analyser.connect(context.destination)
      let started = false
      const startTime = context.currentTime
      const markStarted = () => {
        if (!started && active() && context.state === 'running' && context.currentTime > startTime) {
          started = true
          if (this.startMonitor) clearInterval(this.startMonitor)
          this.startMonitor = null; callbacks.started()
        }
      }
      const values = new Float32Array(analyser.fftSize)
      const sample = () => {
        if (!active()) return
        if (context.state !== 'running') { fail('AUDIO_INTERRUPTED'); return }
        markStarted()
        if (!active()) return
        analyser.getFloatTimeDomainData(values)
        const rms = Math.sqrt(values.reduce((sum, value) => sum + value * value, 0) / values.length)
        if (started) callbacks.level(Math.min(1, rms * 7))
        this.frame = requestAnimationFrame(sample)
      }
      source.onended = () => {
        if (!active()) return
        markStarted()
        if (!active()) return
        this.stop()
        if (started) callbacks.ended()
        else callbacks.failed('AUDIO_INTERRUPTED')
      }
      context.onstatechange = () => { if (active() && context.state !== 'running') fail('AUDIO_INTERRUPTED') }
      try { source.start() } catch { fail('PLAYBACK_BLOCKED'); return }
      this.timer = setTimeout(() => fail('PLAYBACK_TIMEOUT'), Math.ceil(buffer.duration * 1000) + 3000)
      // Timers and the audio clock/onended, not animation frames, determine STARTED.
      this.startMonitor = setInterval(markStarted, 50)
      this.frame = requestAnimationFrame(sample)
    })().catch(() => fail('PLAYBACK_BLOCKED'))
  }
  stop() {
    this.sequence++
    this.abort?.abort(); this.abort = null
    if (this.timer) clearTimeout(this.timer)
    this.timer = null; cancelAnimationFrame(this.frame)
    if (this.startMonitor) clearInterval(this.startMonitor)
    this.startMonitor = null
    if (this.context) this.context.onstatechange = null
    if (this.gain) { this.gain.gain.value = 0; this.gain.disconnect() }
    if (this.source) { this.source.onended = null; try { this.source.stop() } catch { /* already stopped */ } this.source.disconnect() }
    this.analyser?.disconnect(); this.source = null; this.gain = null; this.analyser = null
  }
  dispose() { this.stop(); void this.context?.close(); this.context = null }
}
