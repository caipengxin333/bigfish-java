<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import PlaceholderAvatar from './PlaceholderAvatar.vue'
import { avatarErrorMessage } from '../stage/live2d/assets.ts'
import type { Expression } from '../stage/live2d/assets.ts'
import { AvatarLifetime } from '../stage/live2d/lifecycle.ts'
import type { AvatarRenderer } from '../stage/live2d/lifecycle.ts'
import type { AvatarSettings } from '../stage/avatar-settings.ts'
import { loadCore } from '../stage/live2d/core.ts'

const props = defineProps<{ mouthLevel: number; speaking: boolean; connected: boolean; settings: AvatarSettings; expression: string }>()
const emit = defineEmits<{ catalog: [Expression[]]; retry: [] }>()
const canvas = ref<HTMLCanvasElement>(), renderer = shallowRef<AvatarRenderer>(), error = ref(''), expressionError = ref(''), warnings = ref<string[]>([])
const lifetime = new AvatarLifetime()
function fail(value: unknown) { error.value = avatarErrorMessage(value); lifetime.dispose(); renderer.value = undefined; emit('catalog', []) }
function update() { renderer.value?.update(props, props.settings) }
watch(() => [props.mouthLevel, props.speaking, props.connected, props.settings], update, { deep: true, flush: 'sync' })
watch(() => props.expression, async name => {
  expressionError.value = ''
  try { await renderer.value?.expression(name) }
  catch { if (renderer.value && props.expression === name) expressionError.value = '表情加载失败，保持当前形象。可恢复默认表情后重试。' }
})
onMounted(async () => {
  try {
    const result = await lifetime.load(async signal => {
      await loadCore(); signal.throwIfAborted()
      const { createRenderer } = await import('../stage/live2d/runtime.ts')
      signal.throwIfAborted()
      return createRenderer(canvas.value!, signal, fail)
    })
    if (result) { renderer.value = result; warnings.value = result.warnings; emit('catalog', result.expressions); update() }
  } catch (value) { fail(value) }
})
onBeforeUnmount(() => { lifetime.dispose(); renderer.value = undefined })
</script>
<template>
  <div class="live2d-host" :data-avatar-state="error ? 'fallback' : renderer ? 'ready' : 'loading'">
    <canvas ref="canvas" class="live2d-canvas" :class="{ loaded: !!renderer }" role="img" aria-label="BigFish Live2D 形象，嘴型由实际播放音量驱动" :aria-hidden="!renderer" />
    <PlaceholderAvatar v-if="!renderer" :mouth-level="mouthLevel" :speaking="speaking" :connected="connected" />
  </div>
  <div v-if="error" class="message warning avatar-load-status" role="alert"><p>{{ error }}</p><button class="secondary" @click="emit('retry')">重新加载形象</button></div>
  <p v-else-if="!renderer" class="muted avatar-load-status" role="status">正在加载本机 Live2D 模型（8192 × 8192 贴图）；加载期间使用占位形象。</p>
  <p v-for="warning in warnings" v-show="!!renderer" :key="warning" class="message warning">{{ warning }}</p>
  <p v-if="expressionError" class="message warning" role="alert">{{ expressionError }}</p>
</template>
