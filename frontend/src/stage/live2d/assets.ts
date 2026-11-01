export const modelRoot = '/avatar-assets/models/BigFish/'
export const modelUrl = modelRoot + 'BigFish.model3.json'
export const coreUrl = '/avatar-assets/sdk/live2dcubismcore.min.js'
export class AvatarError extends Error {
  readonly code: string
  constructor(code: string) { super(code); this.code = code }
}
export function assetPath(path: unknown): string {
  if (typeof path !== 'string' || !path || path.length > 240 || /[\\%?#:\u0000-\u001f]/.test(path) || path.startsWith('/') || path.split('/').some(p => !p || p === '.' || p === '..')) throw new AvatarError('MODEL_INVALID')
  return modelRoot + path.split('/').map(encodeURIComponent).join('/')
}
export interface Expression { name: string; path: string; restricted: boolean }
export interface Manifest { moc: string; textures: string[]; physics?: string; expressions: Expression[]; lips: string[]; eyes: string[] }
export function parseManifest(buffer: ArrayBuffer): Manifest {
  try {
    const value = JSON.parse(new TextDecoder().decode(buffer)), refs = value.FileReferences
    if (value.Version !== 3 || !refs || !Array.isArray(refs.Textures) || refs.Textures.length < 1 || refs.Textures.length > 4) throw new Error()
    const groups = value.Groups ?? []
    if (!Array.isArray(groups) || groups.length > 32) throw new Error()
    const ids = (name: string): string[] => {
      const result = groups.filter(g => g?.Target === 'Parameter' && g.Name === name).flatMap(g => g.Ids)
      if (result.length > 32 || result.some(id => typeof id !== 'string' || !/^[a-zA-Z0-9_\-]{1,100}$/.test(id))) throw new Error()
      return [...new Set(result)]
    }
    if (refs.Expressions && (!Array.isArray(refs.Expressions) || refs.Expressions.length > 32)) throw new Error()
    const expressions = (refs.Expressions ?? []).map((entry: { Name: unknown; File: unknown }) => {
      if (typeof entry.Name !== 'string' || !entry.Name || entry.Name.length > 80 || /[<>\u0000-\u001f]/.test(entry.Name)) throw new Error()
      const path = assetPath(entry.File)
      return { name: entry.Name, path, restricted: /水印|署名|版权|watermark|credit|copyright/i.test(entry.Name + String(entry.File)) }
    })
    return { moc: assetPath(refs.Moc), textures: refs.Textures.map(assetPath), physics: refs.Physics ? assetPath(refs.Physics) : undefined, expressions, lips: ids('LipSync'), eyes: ids('EyeBlink') }
  } catch { throw new AvatarError('MODEL_INVALID') }
}
export async function fetchAsset(path: string, signal: AbortSignal, maxBytes: number): Promise<ArrayBuffer> {
  if (!path.startsWith(modelRoot)) throw new AvatarError('MODEL_INVALID')
  const timeout = AbortSignal.timeout(30_000)
  try {
    const response = await fetch(path, { signal: AbortSignal.any([signal, timeout]), redirect: 'error', credentials: 'same-origin' })
    if (!response.ok || !response.body) throw new AvatarError(response.status === 404 ? 'ASSET_MISSING' : 'ASSET_FAILED')
    const reader = response.body.getReader(), chunks: Uint8Array[] = []
    let size = 0
    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        size += value.byteLength
        if (size > maxBytes) throw new AvatarError('ASSET_TOO_LARGE')
        chunks.push(value)
      }
    } finally { await reader.cancel().catch(() => {}); reader.releaseLock() }
    const output = new Uint8Array(size)
    let offset = 0
    for (const chunk of chunks) { output.set(chunk, offset); offset += chunk.byteLength }
    return output.buffer
  } catch (error) {
    if (signal.aborted) throw new AvatarError('CANCELLED')
    if (timeout.aborted) throw new AvatarError('ASSET_TIMEOUT')
    throw error instanceof AvatarError ? error : new AvatarError('ASSET_FAILED')
  }
}
export function avatarErrorMessage(error: unknown): string {
  const code = error instanceof AvatarError ? error.code : 'RENDER_FAILED'
  const messages: Record<string, string> = {
    CORE_FAILED: 'Cubism Core 缺失、版本不符或被浏览器阻止，请检查本机 SDK 资源。',
    ASSET_MISSING: '本机模型文件缺失，请检查 avatar-assets 目录。', ASSET_FAILED: '本机模型资源读取失败，请检查资源服务后重试。',
    ASSET_TIMEOUT: '模型加载超时，请检查资源服务后重试。', ASSET_TOO_LARGE: '模型资源超过加载上限。',
    MODEL_INVALID: '模型配置不兼容或资源路径不合法。', MOC_INCOMPATIBLE: '模型 MOC 与当前 Cubism Core 不兼容或文件损坏。',
    WEBGL_UNAVAILABLE: '浏览器无法启用 WebGL，请检查硬件加速或更换浏览器。', TEXTURE_UNSUPPORTED: '显卡不支持此模型的 8192 级贴图或纹理内存不足。',
    CONTEXT_LOST: '图形上下文已丢失，请重试加载形象。', RENDER_FAILED: 'Live2D 渲染失败，请重试加载形象。',
  }
  return (Object.hasOwn(messages, code) ? messages[code] : messages.RENDER_FAILED) + ' 已回退占位形象，声音链路不受影响。'
}
