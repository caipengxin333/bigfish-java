import type { Bootstrap, ConfigSnapshot, ProvidersView, RuntimeDiagnosticsView, RuntimeView } from '../src/contracts.ts'
export const snapshot: ConfigSnapshot = { version: 1, updatedAt: '2026-11-01T10:00:00Z', settings: {
  profileName: 'test', persona: '', roomId: '123456', model: 'mock', maxOutputTokens: 128, voice: 'mock', readComment: true,
  welcomeIntervalSeconds: 30, synthesisQueueCapacity: 8, playbackQueueCapacity: 8, priorities: { gift: 60, comment: 50, follow: 10, entrance: 5 },
} }
export const bootstrap: Bootstrap = { application: 'fixture', version: 'test', mode: 'READY', sessionState: 'STOPPED', capabilities: { douyinCollector: true } }
const provider = { enabled: true, configured: true, state: 'UNVERIFIED', reason: 'LIVE_RECEIVE_NOT_VERIFIED' }
export const providers: ProvidersView = { deepseek: { ...provider }, aliyun: { ...provider }, douyin: { ...provider },
  aliyunVoices: ['xiaoyun'], speechTextLimit: 300, audioDurationLimitSeconds: 25,
  aliyunToken: { mode: 'STATIC', status: 'UNVERIFIED', expiresAt: null, remainingSeconds: null, refreshResult: 'NOT_REQUESTED',
    lastError: '', lastAttemptAt: null, lastSuccessAt: null, retryAfterMillis: 0, attempts: 0 },
}
const queue = { depth: 0, capacity: 8 }
export const runtime: RuntimeView = { sessionId: '', state: 'STOPPED', mode: 'MOCK', conversationMode: 'MOCK', speechMode: 'LOCAL_TEST_TONE', roomId: '',
  savedConfigVersion: 1, appliedConfigVersion: 0, queues: { comments: queue, templates: queue, synthesis: queue, playback: queue, modelActive: false, speechActive: false },
  player: { connected: false, ready: false, playing: false, leaseGeneration: 0 },
  collector: { mode: 'MOCK', state: 'STOPPED', received: 0, emitted: 0, duplicates: 0, rejected: 0, lastError: '' },
  dropped: 0, failures: 0, lastError: '', lastErrorAt: '',
}
const worker = { threads: 1, active: 0, queued: 0, completed: 0 }
export const diagnostics: RuntimeDiagnosticsView = { sessionId: '', state: 'STOPPED', capturedAt: '2026-11-01T10:00:00Z', waiting: ['STOPPED'],
  lastReceivedAt: null, lastModelSuccessAt: null, lastSpeechSuccessAt: null, lastPlaybackSuccessAt: null, lastFailure: null,
  recent: [], taskEvictions: 0, historyEvictions: 0, audioAssets: 0, dedupEntries: 0, modelWorker: worker, speechWorker: worker,
}
