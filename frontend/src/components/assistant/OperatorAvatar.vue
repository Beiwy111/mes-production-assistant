<script setup>
import { computed } from 'vue'
const props = defineProps({ username: { type: String, default: '' }, name: { type: String, default: '' } })
const seed = computed(() => [...props.username].reduce((n,c) => ((n * 31 + c.codePointAt(0)) >>> 0), 7))
const hue = computed(() => seed.value % 360)
</script>
<template>
  <svg class="operator-avatar" viewBox="0 0 40 40" aria-hidden="true">
    <circle cx="20" cy="20" r="20" :fill="`hsl(${hue} 55% 91%)`"/>
    <path d="M7 40v-5c0-8 26-8 26 0v5" :fill="`hsl(${hue} 40% 43%)`"/>
    <ellipse cx="20" cy="18" rx="9" ry="11" fill="#efd4bb"/>
    <path :d="seed % 2 ? 'M11 19V13Q12 3 24 7L30 15L24 11L11 15Z' : 'M11 17Q7 6 20 6Q33 6 29 18L26 11L13 13Z'" :fill="`hsl(${seed % 35} 24% ${18 + seed % 20}%)`"/>
    <path d="M16 19h1m6 0h1" stroke="#3a3738" stroke-width="2" stroke-linecap="round"/>
    <path v-if="seed % 3 === 0" d="M12 16h7v6h-7zm9 0h7v6h-7zm-2 2h2" fill="none" stroke="#4b5265"/>
    <path d="M17 25q3 2 6 0" fill="none" stroke="#a46c60" stroke-linecap="round"/>
    <circle cx="33" cy="33" r="7" fill="#fff"/><text x="33" y="36" text-anchor="middle" font-size="9" :fill="`hsl(${hue} 35% 35%)`">{{ name.slice(0,1) }}</text>
  </svg>
</template>
<style scoped>.operator-avatar { width:32px;height:32px;flex-shrink:0; }</style>
