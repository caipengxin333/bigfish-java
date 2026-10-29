<script setup lang="ts">
defineProps<{ mouthLevel: number; speaking: boolean; connected: boolean }>()
</script>
<template>
  <svg class="avatar" viewBox="0 0 440 350" role="img" aria-label="BigFish 原创鱼形占位角色，嘴型由音频音量驱动">
    <defs><linearGradient id="fish-body" x1="0" x2="1" y1="0" y2="1"><stop stop-color="#73d4bd"/><stop offset="1" stop-color="#219b89"/></linearGradient></defs>
    <ellipse cx="220" cy="304" rx="110" ry="12" fill="#173c3b" opacity=".1"/>
    <g :class="{ breathing: connected && !speaking }">
      <path d="M116 171 Q55 114 53 136 L59 233 Q80 249 122 205" fill="#2ca590"/>
      <path d="M180 100 Q224 43 267 113" fill="#46b8a0"/>
      <ellipse cx="228" cy="181" rx="126" ry="101" fill="url(#fish-body)"/>
      <ellipse cx="245" cy="208" rx="88" ry="63" fill="#d4f5df"/>
      <ellipse cx="192" cy="160" rx="13" ry="19" fill="#163f40"/><ellipse cx="280" cy="160" rx="13" ry="19" fill="#163f40"/>
      <circle cx="196" cy="154" r="4" fill="white"/><circle cx="284" cy="154" r="4" fill="white"/>
      <ellipse cx="166" cy="190" rx="15" ry="8" fill="#f6b8a1" opacity=".8"/><ellipse cx="306" cy="190" rx="15" ry="8" fill="#f6b8a1" opacity=".8"/>
      <ellipse data-testid="mouth" cx="237" cy="209" :rx="12 + mouthLevel * 5" :ry="2 + mouthLevel * 20" fill="#194b47"/>
      <path d="M143 212 Q113 229 145 244 Q176 234 166 219" fill="#269c88"/>
    </g>
  </svg>
</template>
