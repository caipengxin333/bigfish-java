export const avatarStorageKey = 'bigfish.browser.avatar.v1'
export interface AvatarSettings { mode: 'placeholder' | 'live2d'; size: number; x: number; y: number; background: 'mint' | 'light' | 'dark' | 'transparent' }
export const defaultAvatarSettings: Readonly<AvatarSettings> = { mode: 'placeholder', size: 1, x: 0, y: 0, background: 'mint' }
export function parseAvatarSettings(raw: string | null): AvatarSettings {
  const fallback = { ...defaultAvatarSettings }
  try {
    const value = JSON.parse(raw ?? 'null')
    if (!value || value.version !== 1) return fallback
    const bounded = (key: string, min: number, max: number, defaultValue: number) => typeof value[key] === 'number' && Number.isFinite(value[key]) && value[key] >= min && value[key] <= max ? value[key] : defaultValue
    return { mode: value.mode === 'live2d' ? 'live2d' : 'placeholder', size: bounded('size', .5, 1.2, 1), x: bounded('x', -100, 100, 0), y: bounded('y', -100, 100, 0),
      background: ['mint', 'light', 'dark', 'transparent'].includes(value.background) ? value.background : 'mint' }
  } catch { return fallback }
}
export function serializeAvatarSettings(value: AvatarSettings): string {
  // Reconstruct the allowlist; never serialize extra properties or a whole application config.
  return JSON.stringify({ version: 1, ...parseAvatarSettings(JSON.stringify({ version: 1, mode: value.mode, size: value.size, x: value.x, y: value.y, background: value.background })) })
}
