// Imported only after the separately hosted, pinned Core has loaded.
import { CubismFramework } from '@cubism/live2dcubismframework'
import { CubismUserModel } from '@cubism/model/cubismusermodel'
import { CubismEyeBlink } from '@cubism/effect/cubismeyeblink'
import { CubismBreath, BreathParameterData } from '@cubism/effect/cubismbreath'
import { csmVector } from '@cubism/type/csmvector'
import { CubismMatrix44 } from '@cubism/math/cubismmatrix44'
import { ACubismMotion } from '@cubism/motion/acubismmotion'
import type { CubismIdHandle } from '@cubism/id/cubismid'
import { AvatarError, fetchAsset, modelUrl, parseManifest } from './assets.ts'
import type { Manifest } from './assets.ts'
import { MouthEnvelope, RenderLoop, mappedParameters } from './lifecycle.ts'
import type { AvatarInput, AvatarRenderer } from './lifecycle.ts'
import { defaultAvatarSettings } from '../avatar-settings.ts'
import { trackGraphicsResources } from './gl-resources.ts'
import type { AvatarSettings } from '../avatar-settings.ts'

let owners = 0
function retainFramework(): () => void {
  if (!owners) { CubismFramework.startUp(); CubismFramework.initialize() }
  owners++
  return () => { if (--owners === 0) { CubismFramework.dispose(); CubismFramework.cleanUp() } }
}

class StageModel extends CubismUserModel {
  private defaults: number[] = []
  private lipIds: CubismIdHandle[] = []
  initializeMapping(manifest: Manifest): string[] {
    const known = new Set<string>(), warnings: string[] = []
    for (let i = 0; i < this._model.getParameterCount(); i++) {
      known.add(this._model.getParameterId(i).getString().s)
      this.defaults.push(this._model.getParameterDefaultValue(i))
    }
    const handles = new Map([...known].map(id => [id, CubismFramework.getIdManager().getId(id)]))
    const resolve = (ids: string[]) => mappedParameters(ids, handles)
    this.lipIds = resolve(manifest.lips)
    const eyes = resolve(manifest.eyes), blinkIds = new csmVector<CubismIdHandle>()
    eyes.forEach(id => blinkIds.pushBack(id))
    this._eyeBlink = CubismEyeBlink.create(); this._eyeBlink.setParameterIds(blinkIds)
    if (!this.lipIds.length || this.lipIds.length !== manifest.lips.length) warnings.push('模型未提供完整有效的 LipSync 映射，无法确认全部嘴型。')
    if (!eyes.length || eyes.length !== manifest.eyes.length) warnings.push('模型未提供完整有效的 EyeBlink 映射，无法确认自动眨眼。')
    const breath = new csmVector<BreathParameterData>()
    // Conventional idle parameters are optional and only used when present, never for mouth/eyes.
    for (const [name, peak, cycle] of [['ParamAngleX', 3, 6.5], ['ParamAngleY', 2, 5.3], ['ParamAngleZ', 1.5, 7.1], ['ParamBodyAngleX', 1, 8.2], ['ParamBreath', .25, 3.8]] as const) {
      if (known.has(name) && !manifest.lips.includes(name) && !manifest.eyes.includes(name)) breath.pushBack(new BreathParameterData(CubismFramework.getIdManager().getId(name), name === 'ParamBreath' ? .25 : 0, peak, cycle, 1))
    }
    this._breath = CubismBreath.create(); this._breath.setParameters(breath)
    return warnings
  }
  selectExpression(motion?: ACubismMotion): void {
    this._expressionManager.stopAllMotions()
    if (motion) this._expressionManager.startMotion(motion, false)
  }
  advance(seconds: number, mouth: number): void {
    for (let i = 0; i < this.defaults.length; i++) this._model.setParameterValueByIndex(i, this.defaults[i])
    this._eyeBlink?.updateParameters(this._model, seconds)
    this._breath?.updateParameters(this._model, seconds)
    this._expressionManager.updateMotion(this._model, seconds)
    this._physics?.evaluate(this._model, seconds)
    // LipSync has the final word. Physics, idle and expressions cannot reopen a cancelled mouth.
    for (const id of this.lipIds) this._model.setParameterValueById(id, mouth)
    this._model.update()
  }
}

async function decodeTexture(bytes: ArrayBuffer, signal: AbortSignal): Promise<HTMLImageElement> {
  const url = URL.createObjectURL(new Blob([bytes], { type: 'image/png' })), image = new Image()
  try {
    await new Promise<void>((resolve, reject) => {
      const cleanup = () => { clearTimeout(timer); signal.removeEventListener('abort', abort); image.onload = image.onerror = null }
      const abort = () => { cleanup(); image.src = ''; reject(new AvatarError('CANCELLED')) }
      const timer = setTimeout(() => { cleanup(); image.src = ''; reject(new AvatarError('ASSET_TIMEOUT')) }, 15_000)
      image.onload = () => { cleanup(); resolve() }
      image.onerror = () => { cleanup(); reject(new AvatarError('MODEL_INVALID')) }
      signal.addEventListener('abort', abort, { once: true })
      if (signal.aborted) abort(); else image.src = url
    })
    return image
  } finally { URL.revokeObjectURL(url) }
}

export async function createRenderer(canvas: HTMLCanvasElement, signal: AbortSignal, failed: (error: unknown) => void): Promise<AvatarRenderer> {
  signal.throwIfAborted()
  const gl = canvas.getContext('webgl', { alpha: true, premultipliedAlpha: true, antialias: false, preserveDrawingBuffer: false })
  if (!gl) throw new AvatarError('WEBGL_UNAVAILABLE')
  const releaseGraphics = trackGraphicsResources(gl), releaseFramework = retainFramework(), model = new StageModel(), textures: WebGLTexture[] = [], expressions = new Map<string, ACubismMotion>()
  const requests = new AbortController(), pending = AbortSignal.any([signal, requests.signal])
  let disposed = false, loop: RenderLoop | undefined, expressionRevision = 0
  let input: AvatarInput = { mouthLevel: 0, speaking: false, connected: false }, settings: AvatarSettings = { ...defaultAvatarSettings }
  const mouth = new MouthEnvelope()
  const dispose = () => {
    if (disposed) return
    disposed = true; expressionRevision++; requests.abort(); loop?.stop()
    signal.removeEventListener('abort', dispose); canvas.removeEventListener('webglcontextlost', contextLost)
    model.release(); expressions.forEach(value => ACubismMotion.delete(value)); expressions.clear()
    textures.forEach(texture => gl.deleteTexture(texture)); releaseFramework(); releaseGraphics()
    gl.getExtension('WEBGL_lose_context')?.loseContext()
    canvas.width = canvas.height = 1
  }
  const contextLost = (event: Event) => { event.preventDefault(); dispose(); failed(new AvatarError('CONTEXT_LOST')) }
  canvas.addEventListener('webglcontextlost', contextLost)
  signal.addEventListener('abort', dispose, { once: true })
  try {
    const manifest = parseManifest(await fetchAsset(modelUrl, pending, 1024 * 1024))
    const moc = await fetchAsset(manifest.moc, pending, 8 * 1024 * 1024)
    try { model.loadModel(moc, true); if (!model.getModel()) throw new Error() } catch { throw new AvatarError('MOC_INCOMPATIBLE') }
    if (manifest.physics) { const physics = await fetchAsset(manifest.physics, pending, 2 * 1024 * 1024); model.loadPhysics(physics, physics.byteLength) }
    const warnings = model.initializeMapping(manifest)
    model.createRenderer(); const renderer = model.getRenderer(); renderer.startUp(gl); renderer.setIsPremultipliedAlpha(true)
    for (let index = 0; index < manifest.textures.length; index++) {
      const bytes = await fetchAsset(manifest.textures[index], pending, 40 * 1024 * 1024)
      const image = await decodeTexture(bytes, pending)
      try {
        pending.throwIfAborted()
        const limit = gl.getParameter(gl.MAX_TEXTURE_SIZE) as number
        if (image.width > limit || image.height > limit || image.width > 8192 || image.height > 8192) throw new AvatarError('TEXTURE_UNSUPPORTED')
        const texture = gl.createTexture()
        if (!texture) throw new AvatarError('TEXTURE_UNSUPPORTED')
        textures.push(texture); gl.bindTexture(gl.TEXTURE_2D, texture)
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
        gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, 1)
        gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, image)
        if (gl.getError() !== gl.NO_ERROR) throw new AvatarError('TEXTURE_UNSUPPORTED')
        renderer.bindTexture(index, texture)
      } finally { image.src = '' }
    }
    const draw = (seconds: number) => {
      if (disposed) return
      if (gl.isContextLost()) throw new AvatarError('CONTEXT_LOST')
      const ratio = Math.min(window.devicePixelRatio || 1, 1.5), width = Math.max(1, Math.min(2048, Math.round(canvas.clientWidth * ratio))), height = Math.max(1, Math.min(2048, Math.round(canvas.clientHeight * ratio)))
      if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height }
      const modelAspect = model.getModel().getCanvasWidth() / model.getModel().getCanvasHeight(), aspect = width / height
      // Keep the full canvas (including embedded credit) visible, with room to move within margins.
      const fit = Math.min(1, aspect / modelAspect) * .75 * settings.size
      const projection = new CubismMatrix44()
      projection.scale(fit / aspect, fit)
      projection.translate(settings.x / 100 * Math.max(0, 1 - modelAspect * fit / aspect), -settings.y / 100 * (1 - fit))
      projection.multiplyByMatrix(model.getModelMatrix())
      model.advance(seconds, mouth.update(input, seconds))
      gl.viewport(0, 0, width, height); gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT)
      renderer.setMvpMatrix(projection)
      // SDK accepts the browser's default framebuffer (null); its legacy declaration lacks nullability.
      renderer.setRenderState(null as unknown as WebGLFramebuffer, [0, 0, width, height]); renderer.drawModel()
    }
    pending.throwIfAborted(); draw(0); loop = new RenderLoop(draw, error => { dispose(); failed(error) })
    return { expressions: manifest.expressions, warnings,
      update(value, options) { if (disposed) return; input = { ...value }; settings = { ...options }; if (!value.speaking || !value.connected || !value.mouthLevel) { try { draw(0) } catch (error) { dispose(); failed(error) } } },
      async expression(name) {
        if (disposed) return
        const revision = ++expressionRevision
        if (!name) { model.selectExpression(); draw(0); return }
        const entry = manifest.expressions.find(item => item.name === name && !item.restricted)
        if (!entry) throw new AvatarError('MODEL_INVALID')
        let motion = expressions.get(name)
        if (!motion) {
          const bytes = await fetchAsset(entry.path, pending, 256 * 1024)
          if (disposed || revision !== expressionRevision) return
          motion = model.loadExpression(bytes, bytes.byteLength, name)
          if (!motion) throw new AvatarError('MODEL_INVALID')
          expressions.set(name, motion)
        }
        if (!disposed && revision === expressionRevision) { model.selectExpression(motion); draw(0) }
      }, dispose }
  } catch (error) { dispose(); throw error }
}
