import type { Bootstrap, ConfigSnapshot, Settings } from './contracts'
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api/v1${path}`, { ...init, signal: AbortSignal.timeout(8000), headers: { 'Content-Type': 'application/json' } })
  const body = await response.json()
  if (!response.ok) throw new Error(body.message || `请求失败：${response.status}`)
  return body as T
}
export const api = {
  bootstrap: () => request<Bootstrap>('/bootstrap'),
  config: () => request<ConfigSnapshot>('/config'),
  save: (expectedVersion: number, settings: Settings) => request<ConfigSnapshot>('/config', { method: 'PUT', body: JSON.stringify({ expectedVersion, settings }) }),
}
