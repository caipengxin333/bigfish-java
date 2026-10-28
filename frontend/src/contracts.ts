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
