import type { Component, InjectionKey } from 'vue'
// Optional app.provide(stageAvatarKey, YourAvatar). Props: mouthLevel (0..1), speaking, connected.
// Avatar renderers must never play audio themselves; StageController owns the audio lifecycle.
export const stageAvatarKey: InjectionKey<Component> = Symbol('BigFish.stageAvatar')
