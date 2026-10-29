import type { Bootstrap, ConfigSnapshot, Settings } from '../contracts'
import { copySettings, isSnapshot, RequestError } from '../api.ts'
const key = 'bigfish.mock.config.v1'
const initial: ConfigSnapshot = { version: 1, updatedAt: new Date().toISOString(), settings: {
  profileName: '本地演示配置', persona: '你是一位亲切、简洁的直播助手。', roomId: '', model: 'mock-no-model',
  maxOutputTokens: 256, voice: 'mock-tone', readComment: true, welcomeIntervalSeconds: 30,
  synthesisQueueCapacity: 8, playbackQueueCapacity: 8, priorities: { gift: 60, comment: 50, follow: 10, entrance: 5 },
} }
export const mockConfig = {
  bootstrap: async (): Promise<Bootstrap> => ({ application: 'BigFish browser demo', version: 'local', mode: 'SCAFFOLD', sessionState: 'STOPPED', capabilities: {} }),
  config: async (): Promise<ConfigSnapshot> => {
    const raw = localStorage.getItem(key)
    if (!raw) return structuredClone(initial)
    const value: unknown = JSON.parse(raw)
    if (!isSnapshot(value)) throw new RequestError('invalid', '本地 mock 配置无法读取。请检查浏览器存储。')
    return value
  },
  save: async (expectedVersion: number, settings: Settings): Promise<ConfigSnapshot> => {
    if (!navigator.locks) throw new RequestError('invalid', '浏览器不支持 mock 配置保存锁，未保存。请使用支持 Web Locks 的本地浏览器。')
    return navigator.locks.request('bigfish.mock.config.save.v1', async () => {
      const current = await mockConfig.config()
      if (current.version !== expectedVersion) throw new RequestError('conflict', '保存冲突：mock 配置已被另一页面更新。草稿已保留。')
      const saved = { version: current.version + 1, updatedAt: new Date().toISOString(), settings: copySettings(settings) }
      if (!isSnapshot(saved)) throw new RequestError('invalid', 'mock 配置校验失败，请检查字段范围。')
      localStorage.setItem(key, JSON.stringify(saved))
      return saved
    })
  },
}
