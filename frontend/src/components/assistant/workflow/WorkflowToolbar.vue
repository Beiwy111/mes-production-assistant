<script setup>
import { ArrowDownWideNarrow, ArrowLeft, ArrowRight, FolderOpen, RotateCcw, ScanLine } from 'lucide-vue-next'
defineProps({ title: String, direction: String, mainOnly: Boolean, overview: Boolean, parentTitle: String,
  nodes: Array, selected: String })
defineEmits(['direction', 'main', 'reset', 'locate', 'focus', 'back', 'library'])
</script>
<template>
  <header class="toolbar">
    <div class="top">
      <button class="open-lib" aria-label="打开工作流目录" title="打开工作流目录" @click="$emit('library')"><FolderOpen :size="16"/></button>
      <div class="titles"><button v-if="parentTitle" class="back" title="返回上一级" @click="$emit('back')"><ArrowLeft :size="13"/> {{ title }}</button>
        <h3>{{ parentTitle || title }}</h3></div>
      <button class="reset" title="恢复自动布局与默认视角" @click="$emit('reset')"><RotateCcw :size="14"/><span>重置布局</span></button>
    </div>
    <div class="actions">
      <label class="choose" title="布局方向"><ArrowRight v-if="direction==='horizontal'" :size="13"/><ArrowDownWideNarrow v-else :size="13"/>
        <select aria-label="布局方向" :value="direction" @change="$emit('direction',$event.target.value)">
          <option value="horizontal">横向布局</option><option value="vertical">纵向布局</option>
        </select>
      </label>
      <label v-if="!overview" class="choose" title="主链路展示从入口出发的最长结构路径"><select aria-label="链路范围" :value="mainOnly ? 'main' : 'all'" @change="$emit('main',$event.target.value==='main')">
        <option value="all">完整链路</option><option value="main">仅主链路</option>
      </select></label>
      <label class="choose locator"><select aria-label="定位节点" :value="selected" @change="$emit('locate',$event.target.value)">
        <option value="">定位节点</option><option v-for="node in nodes" :key="node.id" :value="node.id">{{ node.label }}</option>
      </select></label>
      <button class="focus" :disabled="!selected" title="聚焦当前节点" aria-label="聚焦当前节点" @click="$emit('focus')"><ScanLine :size="15"/></button>
    </div>
  </header>
</template>
<style scoped>
.toolbar{flex:none;padding:12px 15px 10px;background:#fff;border-bottom:1px solid #dee7eb;color:#233c47}
.top,.actions,.choose,button{display:flex;align-items:center}.top,.actions{gap:7px;min-width:0}.actions{flex-wrap:wrap;margin-top:9px}
.titles{min-width:0}.titles h3{margin:0;font-size:16px;font-weight:650;white-space:nowrap;text-overflow:ellipsis;overflow:hidden}
button,.choose{border:1px solid #d8e3e8;border-radius:6px;background:#fff;color:#4e6570;font:inherit}
button{gap:5px;padding:5px 8px;font-size:12px;cursor:pointer}button:hover:not(:disabled),.choose:hover{background:#eff7f5;border-color:#b7d6d0}button:disabled{opacity:.45;cursor:default}
.reset{margin-left:auto;white-space:nowrap}.back{border:none;padding:0;margin-bottom:3px;color:#137c71;font-size:11px}.choose{gap:0;padding-left:6px;font-size:11px}.choose select{max-width:100%;height:27px;padding:0 3px;background:transparent;color:inherit;border:0;font:inherit;cursor:pointer}.choose:not(:has(svg)){padding-left:2px}
.locator{max-width:min(174px,52%)}.focus{height:29px;padding:5px 6px}
.open-lib{display:none}
@container workflow (max-width:880px){.open-lib{display:flex}.toolbar{padding:10px 12px}}
</style>
