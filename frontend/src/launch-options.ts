import { ref } from 'vue'
import type { SessionSelection } from './contracts.ts'

export const launchOptionsKey = 'bigfish.browser.launch-options.v1'
export const defaultSelection = (): SessionSelection => ({ mode: 'MOCK', conversationMode: 'MOCK', speechMode: 'LOCAL_TEST_TONE' })
export function selection(value: unknown): SessionSelection | null {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null
  const v = value as Record<string, unknown>
  if (typeof v.mode !== 'string' || typeof v.conversationMode !== 'string' || typeof v.speechMode !== 'string' || !['MOCK', 'DOUYIN'].includes(String(v.mode)) || !['MOCK', 'DEEPSEEK'].includes(String(v.conversationMode)) ||
    !['LOCAL_TEST_TONE', 'ALIYUN_NLS'].includes(String(v.speechMode))) return null
  // Never persist additional properties, credentials, room drafts, or a whole config snapshot.
  return { mode: v.mode, conversationMode: v.conversationMode, speechMode: v.speechMode } as SessionSelection
}
export function createLaunchOptions(storage: Pick<Storage, 'getItem' | 'setItem'> = {
  getItem: key => window.localStorage.getItem(key), setItem: (key, value) => window.localStorage.setItem(key, value),
}) {
  const options = ref(defaultSelection()), error = ref('')
  function reload() {
    try {
      const raw = storage.getItem(launchOptionsKey)
      if (raw === null) { options.value = defaultSelection(); error.value = ''; return }
      if (raw.length > 1024) throw new Error('invalid')
      const data = JSON.parse(raw), parsed = data?.version === 1 ? selection(data.options) : null
      if (!parsed) throw new Error('invalid')
      options.value = parsed; error.value = ''
    } catch { options.value = defaultSelection(); error.value = '本浏览器启动选项无法读取，暂用默认模拟选项；请在管理页重新保存。' }
  }
  function save(value: unknown) {
    const parsed = selection(value)
    if (!parsed) { error.value = '启动选项无效，未保存。'; return false }
    try { storage.setItem(launchOptionsKey, JSON.stringify({ version: 1, options: parsed })); options.value = parsed; error.value = ''; return true }
    catch { error.value = '浏览器阻止保存启动选项，请检查存储权限；其他页面尚未同步。'; return false }
  }
  function onStorage(event: Pick<StorageEvent, 'key'>) { if (event.key === launchOptionsKey || event.key === null) reload() }
  return { options, error, reload, save, onStorage }
}
