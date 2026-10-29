<script setup lang="ts">
import { defineAsyncComponent } from 'vue'
import { mode, pageUrl } from './mode'
import AdminPage from './components/AdminPage.vue'
const StagePage = defineAsyncComponent(() => import('./components/StagePage.vue'))
const isStage = window.location.pathname === '/stage' || window.location.pathname === '/stage/'
</script>
<template>
  <div class="shell">
    <header><a class="brand" :href="pageUrl('admin')"><span class="mark">◒</span> BIGFISH <small>直播工作台</small></a><nav aria-label="主导航"><a :aria-current="!isStage ? 'page' : undefined" :href="pageUrl('admin')">管理配置</a><a :aria-current="isStage ? 'page' : undefined" :href="pageUrl('stage')">播放舞台 ↗</a></nav></header>
    <div class="mode-bar"><span class="badge" :class="{ demo: mode === 'mock' }">{{ mode === 'mock' ? 'MOCK · 浏览器本地演示' : 'REAL · 真实后端接口' }}</span><span class="muted">{{ mode === 'mock' ? '测试音，不连接抖音、不调用模型或 TTS' : '连接状态以实际服务响应为准' }}</span></div>
    <StagePage v-if="isStage" /><AdminPage v-else />
    <footer>BIGFISH JAVA <span>管理无声 · 舞台独占播放</span></footer>
  </div>
</template>
