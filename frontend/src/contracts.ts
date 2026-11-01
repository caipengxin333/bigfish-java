// Shared v1 contract. Change only through the integration task; never copy API credentials here.
export interface Settings {
  profileName: string; persona: string; roomId: string; model: string; maxOutputTokens: number;
  voice: string; readComment: boolean; welcomeIntervalSeconds: number;
  synthesisQueueCapacity: number; playbackQueueCapacity: number;
  priorities: { gift: number; comment: number; follow: number; entrance: number }
}
export interface ConfigSnapshot { version: number; updatedAt: string; settings: Settings }
export interface Bootstrap {
  application: string; version: string; mode: 'SCAFFOLD' | 'READY'; sessionState: string;
  capabilities: Record<string, boolean>
}

export interface QueueStatus { depth: number; capacity: number }
export type CollectionMode = 'MOCK' | 'DOUYIN'
export type CollectorState = 'STOPPED' | 'CONNECTING' | 'CONNECTED' | 'RECONNECTING' | 'ENDED' | 'FAILED'
export interface SessionSelection { mode: CollectionMode; conversationMode: ConversationMode; speechMode: SpeechMode }
export type ConversationMode = 'MOCK' | 'DEEPSEEK'
export type SpeechMode = 'LOCAL_TEST_TONE' | 'ALIYUN_NLS'
export interface ProviderState { configured: boolean; enabled: boolean; state: string; reason: string }
export interface ProvidersView {
  deepseek: ProviderState; aliyun: ProviderState; douyin: ProviderState;
  aliyunVoices: string[]; speechTextLimit: number; audioDurationLimitSeconds: number;
  aliyunToken: NlsTokenState;
}
export interface NlsTokenState {
  mode: 'STATIC' | 'RAM' | 'INVALID'; status: string; expiresAt: string | null; remainingSeconds: number | null;
  refreshResult: string; lastError: string; lastAttemptAt: string | null; lastSuccessAt: string | null;
  retryAfterMillis: number; attempts: number;
}
export interface DiagnosticTrace {
  sequence: number; taskId: string; eventType: string; configVersion: number; stage: string; reason: string;
  at: string; previousStageMs: number; taskElapsedMs: number;
}
export interface RuntimeDiagnosticsView {
  sessionId: string; state: RuntimeView['state']; capturedAt: string; waiting: string[];
  lastReceivedAt: string | null; lastModelSuccessAt: string | null; lastSpeechSuccessAt: string | null; lastPlaybackSuccessAt: string | null;
  lastFailure: DiagnosticTrace | null; recent: DiagnosticTrace[]; taskEvictions: number; historyEvictions: number;
  audioAssets: number; dedupEntries: number;
  modelWorker: { threads: number; active: number; queued: number; completed: number };
  speechWorker: { threads: number; active: number; queued: number; completed: number };
}
export interface ModelCheck {
  authentication: 'NOT_CHECKED' | 'PASSED' | 'FAILED'; selectedModel: 'NOT_CHECKED' | 'PASSED' | 'FAILED';
  generation: 'NOT_CHECKED' | 'PASSED' | 'FAILED'; availableModels: string[]; code: string; elapsedMs: number;
}
export interface RuntimeView {
  sessionId: string; state: 'STOPPED' | 'STARTING' | 'RUNNING' | 'STOPPING' | 'FAILED';
  mode: CollectionMode; conversationMode: ConversationMode; speechMode: SpeechMode; roomId: string;
  savedConfigVersion: number; appliedConfigVersion: number;
  queues: { comments: QueueStatus; templates: QueueStatus; synthesis: QueueStatus; playback: QueueStatus; modelActive: boolean; speechActive: boolean };
  player: { connected: boolean; ready: boolean; playing: boolean; leaseGeneration: number };
  collector: { mode: CollectionMode; state: CollectorState; received: number; emitted: number; duplicates: number; rejected: number; lastError: string };
  dropped: number; failures: number; lastError: string; lastErrorAt: string;
}
export type EventResult = 'ACCEPTED' | 'DUPLICATE' | 'REJECTED_FULL' | 'REJECTED_SESSION' | 'INVALID'
