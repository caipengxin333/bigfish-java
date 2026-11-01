<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import PlaceholderAvatar from './PlaceholderAvatar.vue'
import Live2DAvatar from './Live2DAvatar.vue'
import { avatarStorageKey, defaultAvatarSettings, parseAvatarSettings, serializeAvatarSettings } from '../stage/avatar-settings.ts'
import type { AvatarSettings } from '../stage/avatar-settings.ts'
import type { Expression } from '../stage/live2d/assets.ts'
defineProps<{ mouthLevel: number; speaking: boolean; connected: boolean }>()
const settings = ref<AvatarSettings>({ ...defaultAvatarSettings }), storageError = ref(''), expression = ref(''), catalog = ref<Expression[]>([]), revision = ref(0)
const transform = computed(() => ({ transform: `translate(${settings.value.x * .08}%, ${settings.value.y * .08}%) scale(${settings.value.size * .8})` }))
function read() { try { settings.value = parseAvatarSettings(localStorage.getItem(avatarStorageKey)) } catch { storageError.value = '浏览器存储不可用，形象设置仅在本页生效。' } }
function save() { try { localStorage.setItem(avatarStorageKey, serializeAvatarSettings(settings.value)); storageError.value = '' } catch { storageError.value = '无法保存形象设置，当前选择仅在本页生效。' } }
function storage(event: StorageEvent) { if (event.key === avatarStorageKey || event.key === null) read() }
function reset() { settings.value = { ...defaultAvatarSettings }; expression.value = ''; save() }
function retry() { expression.value = ''; catalog.value = []; revision.value++ }
watch(() => settings.value.mode, () => { expression.value = ''; catalog.value = [] })
onMounted(() => { read(); window.addEventListener('storage', storage) })
onBeforeUnmount(() => window.removeEventListener('storage', storage))
</script>
<template>
  <div class="avatar-area" :class="`avatar-bg-${settings.background}`">
    <Live2DAvatar v-if="settings.mode === 'live2d'" :key="revision" :mouth-level="mouthLevel" :speaking="speaking" :connected="connected" :settings="settings" :expression="expression" @catalog="catalog = $event" @retry="retry" />
    <div v-else class="placeholder-host"><PlaceholderAvatar :style="transform" :mouth-level="mouthLevel" :speaking="speaking" :connected="connected" /></div>
  </div>
  <p v-if="settings.mode === 'live2d'" class="avatar-credit">形象：<a href="https://www.bilibili.com/video/BV1yep465EQz" target="_blank" rel="noopener noreferrer">Mao绒绒家园 · 大肥鱼</a>。仅本机非商业测试，保留水印和署名，不随应用分发。</p>
  <details class="avatar-settings"><summary>舞台形象设置</summary>
    <p class="muted small">只保存本浏览器的形象偏好，不跨浏览器同步，不会启动互动。Live2D 需本机外部资源；缺失时保留占位和声音。</p>
    <div class="avatar-setting-grid">
      <label>形象<select v-model="settings.mode" @change="save"><option value="placeholder">原创 SVG 占位</option><option value="live2d">Live2D · 本机 BigFish</option></select></label>
      <label>背景<select v-model="settings.background" @change="save"><option value="mint">薄荷</option><option value="light">浅色</option><option value="dark">深色</option><option value="transparent">透明</option></select></label>
      <label>大小 {{ Math.round(settings.size * 100) }}%<input v-model.number="settings.size" type="range" min="0.5" max="1.2" step="0.05" @input="save"></label>
      <label>水平位置<input v-model.number="settings.x" type="range" min="-100" max="100" step="1" @input="save"></label>
      <label>垂直位置<input v-model.number="settings.y" type="range" min="-100" max="100" step="1" @input="save"></label>
      <label v-if="settings.mode === 'live2d'">手动表情<select v-model="expression" :disabled="!catalog.length"><option value="">默认表情</option><option v-for="entry in catalog" :key="entry.path" :value="entry.name" :disabled="entry.restricted">{{ entry.name }}{{ entry.restricted ? '（保留署名，不可用）' : '' }}</option></select></label>
    </div>
    <div class="toolbar"><button v-if="settings.mode === 'live2d'" class="secondary" @click="expression = ''">恢复默认表情</button><button v-if="settings.mode === 'live2d'" class="secondary" @click="retry">重新加载形象</button><button class="secondary" @click="reset">恢复形象默认设置</button></div>
    <p v-if="storageError" class="message warning" role="alert">{{ storageError }}</p>
  </details>
</template>
<style>
.avatar-area { border-radius: 18px; overflow: hidden; margin: 1rem 0; }
.avatar-bg-mint { background: radial-gradient(ellipse at center, #eefaf5, #d6ede4); }
.avatar-bg-light { background: #f6f7fa; }.avatar-bg-dark { background: #243344; }.avatar-bg-transparent { background: transparent; }
.live2d-host, .placeholder-host { position: relative; height: clamp(280px, 50vw, 510px); display: grid; place-items: center; }
.live2d-canvas { position: absolute; width: 100%; height: 100%; opacity: 0; }.live2d-canvas.loaded { opacity: 1; }
.live2d-host .avatar, .placeholder-host .avatar { max-height: 100%; margin: 0; }
.avatar-load-status { margin: 0; padding: .75rem 1rem; background: #fff8e7; color: #604715; text-align: left; }
.avatar-credit { font-size: .78rem; text-align: center; overflow-wrap: anywhere; }
.avatar-settings { text-align: left; border-top: 1px solid var(--line, #d6e2dc); padding: .8rem 0; }
.avatar-setting-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: .8rem; margin: 1rem 0; }
.avatar-setting-grid label { min-width: 0; display: grid; gap: .4rem; font-size: .85rem; }.avatar-setting-grid select, .avatar-setting-grid input { width: 100%; min-width: 0; }
</style>
