import { AvatarError, coreUrl } from './assets.ts'
// Official CubismSdkForWeb-5-r.4 Core 05.01.0000. SRI pins the external runtime as well as the source version.
const integrity = 'sha256-Ja6TjLT+KCzhibNXvMl+YD0eH37Hi/BBUNQBwjzceS8='
let loading: Promise<void> | undefined
export function loadCore(): Promise<void> {
  if (loading) return loading
  loading = new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    const finish = (ok: boolean) => {
      clearTimeout(timer); script.onload = null; script.onerror = null
      if (ok) resolve()
      else { script.remove(); loading = undefined; reject(new AvatarError('CORE_FAILED')) }
    }
    const timer = setTimeout(() => finish(false), 15_000)
    script.src = coreUrl; script.integrity = integrity; script.crossOrigin = 'anonymous'; script.async = true
    script.onload = () => finish(typeof Live2DCubismCore !== 'undefined' && Live2DCubismCore.Version.csmGetVersion() === 0x05010000)
    script.onerror = () => finish(false)
    document.head.append(script)
  })
  return loading
}
