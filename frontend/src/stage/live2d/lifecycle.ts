import type { AvatarSettings } from '../avatar-settings.ts'
import type { Expression } from './assets.ts'
export interface AvatarInput { mouthLevel: number; speaking: boolean; connected: boolean }
export interface AvatarRenderer {
  expressions: Expression[]; warnings: string[]
  update(input: AvatarInput, settings: AvatarSettings): void
  expression(name: string): Promise<void>
  dispose(): void
}
export class MouthEnvelope {
  level = 0
  update(input: AvatarInput, seconds: number): number {
    const target = input.speaking && input.connected && Number.isFinite(input.mouthLevel) ? Math.max(0, Math.min(1, input.mouthLevel)) : 0
    // Close synchronously even when animation frames are paused. Never leave a tail after cancellation.
    if (!target) return this.level = 0
    this.level += (target - this.level) * (1 - Math.exp(-Math.max(0, seconds) * 24))
    return this.level
  }
}
export function mappedParameters<T>(declared: string[], known: ReadonlyMap<string, T>): T[] {
  return [...new Set(declared)].filter(id => known.has(id)).map(id => known.get(id)!)
}
/** Visual frames only: never drives audio completion, heartbeats or session state. */
export class RenderLoop {
  private frame = 0
  private previous = 0
  private stopped = false
  constructor(draw: (seconds: number) => void, failed: (error: unknown) => void) {
    const tick = (now: number) => {
      if (this.stopped) return
      try {
        draw(this.previous ? Math.min(.05, Math.max(0, (now - this.previous) / 1000)) : 0)
        this.previous = now
        if (!this.stopped) this.frame = requestAnimationFrame(tick)
      } catch (error) { this.stop(); failed(error) }
    }
    this.frame = requestAnimationFrame(tick)
  }
  stop(): void { this.stopped = true; cancelAnimationFrame(this.frame) }
}
/** Owns one asynchronous renderer. Late completion after replacement must release itself. */
export class AvatarLifetime {
  private abort?: AbortController
  private renderer?: AvatarRenderer
  async load(factory: (signal: AbortSignal) => Promise<AvatarRenderer>): Promise<AvatarRenderer | undefined> {
    this.dispose()
    const abort = this.abort = new AbortController()
    try {
      const renderer = await factory(abort.signal)
      if (abort.signal.aborted) { renderer.dispose(); return }
      this.renderer = renderer
      return renderer
    } catch (error) { if (!abort.signal.aborted) throw error }
  }
  dispose(): void { this.abort?.abort(); this.abort = undefined; this.renderer?.dispose(); this.renderer = undefined }
}
