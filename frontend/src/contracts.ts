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
export interface RuntimeView {
  sessionId: string; state: 'STOPPED' | 'STARTING' | 'RUNNING' | 'STOPPING' | 'FAILED';
  mode: 'MOCK'; conversationMode: 'MOCK' | 'DEEPSEEK'; speechMode: 'LOCAL_TEST_TONE'; roomId: string;
  savedConfigVersion: number; appliedConfigVersion: number;
  queues: { comments: QueueStatus; templates: QueueStatus; synthesis: QueueStatus; playback: QueueStatus; modelActive: boolean; speechActive: boolean };
  player: { connected: boolean; ready: boolean; playing: boolean; leaseGeneration: number };
  collector: { mode: 'MOCK'; state: string; received: number; emitted: number; duplicates: number; rejected: number; lastError: string };
  dropped: number; failures: number;
}
export type EventResult = 'ACCEPTED' | 'DUPLICATE' | 'REJECTED_FULL' | 'REJECTED_SESSION' | 'INVALID'
