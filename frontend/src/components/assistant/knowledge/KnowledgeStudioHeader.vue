<script setup>
import { BookOpen, Expand, FolderInput, Minimize, Share2, X } from 'lucide-vue-next'
defineProps({ tab:String, canIngest:Boolean, maximized:Boolean })
defineEmits(['view', 'maximize', 'close'])
</script>

<template>
  <header class="ks-header">
    <div class="ks-heading"><BookOpen aria-hidden="true"/><h2>知识管理工作台</h2></div>
    <nav aria-label="知识工作台视图">
      <button :aria-pressed="tab==='graph'" @click="$emit('view','graph')"><Share2/>图谱浏览</button>
      <button v-if="canIngest" :aria-pressed="tab==='upload'" @click="$emit('view','upload')"><FolderInput/>资料入库</button>
    </nav>
    <div class="ks-window-actions">
      <button :aria-label="maximized?'退出全屏':'全屏查看'" :title="maximized?'退出全屏':'全屏查看'" @click="$emit('maximize')"><Minimize v-if="maximized"/><Expand v-else/></button>
      <button aria-label="关闭窗口" title="关闭窗口" @click="$emit('close')"><X/></button>
    </div>
  </header>
</template>

<style scoped>
.ks-header { display:flex;flex:none;align-items:stretch;gap:clamp(16px,3vw,54px);min-height:70px;padding:0 20px;border-bottom:1px solid var(--ks-border);background:var(--ks-surface) }
.ks-heading,nav,.ks-window-actions,button { display:flex;align-items:center }
.ks-heading { gap:12px;color:var(--ks-deep);white-space:nowrap }
.ks-heading>svg { width:34px;height:34px;padding:6px;box-sizing:border-box;border-radius:7px;background:var(--ks-deep);color:#fff;stroke-width:1.8 }
h2 { margin:0;font:600 24px/1.25 var(--ks-font);color:var(--ks-strong) }
nav { align-self:stretch;gap:8px }
button { justify-content:center;gap:8px;font:500 14px/1.4 var(--ks-font);background:transparent;color:var(--ks-secondary);border:0;cursor:pointer }
button svg { width:17px;height:17px;flex:none }
nav button { position:relative;height:100%;padding:0 10px;white-space:nowrap }
nav button[aria-pressed=true] { color:var(--ks-deep) }
nav button[aria-pressed=true]::after { content:'';position:absolute;bottom:0;left:9px;right:9px;height:3px;background:var(--ks-brand) }
.ks-window-actions { margin-left:auto;gap:2px }
.ks-window-actions button { width:32px;height:32px;border-radius:5px }
button:hover { color:var(--ks-brand);background:var(--ks-hover) }
@media(max-width:760px){.ks-header{min-height:54px;padding:0 10px;gap:8px}.ks-heading{gap:6px}h2{font-size:17px}.ks-heading>svg{width:25px;height:25px;padding:4px}nav{gap:0}nav button{font-size:13px;padding:0 6px}.ks-window-actions button{width:28px}}
@media(max-width:490px){.ks-header{flex-wrap:wrap;gap:0;align-items:center}.ks-heading{height:44px}.ks-header nav{order:3;width:100%;height:36px}.ks-window-actions{align-self:center}}
</style>
