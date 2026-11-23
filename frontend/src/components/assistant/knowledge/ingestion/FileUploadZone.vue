<script setup>
import { ref } from 'vue'
import { FolderUp, Upload } from 'lucide-vue-next'
import { extensions } from './ingestionApi'
const props = defineProps({ uploading: Boolean })
const emit = defineEmits(['files'])
const picker = ref(null)
const dragDepth = ref(0)
function acceptFiles(files) {
  dragDepth.value = 0
  if (!props.uploading && files?.length) emit('files', Array.from(files))
}
function choose(event) {
  acceptFiles(event.target.files)
  event.target.value = ''
}
</script>

<template>
  <section class="dropzone" :class="{ over: dragDepth > 0 && !uploading }" aria-label="资料上传"
           @dragenter.prevent="dragDepth++" @dragleave.prevent="dragDepth = Math.max(0, dragDepth - 1)" @dragover.prevent @drop.prevent="acceptFiles($event.dataTransfer.files)">
    <div class="upload-icon"><FolderUp :size="25" :stroke-width="1.7"/></div>
    <div class="upload-text"><h3>拖入文件以上传</h3>
      <p>PDF、Word、PowerPoint、Excel、CSV、TXT、Markdown、HTML、JPG、PNG</p>
      <small>支持多选，每份最多 10 MiB</small>
    </div>
    <button type="button" :disabled="uploading" @click="picker.click()"><Upload :size="15"/>{{ uploading ? '正在上传' : '选择文件' }}</button>
    <input ref="picker" hidden type="file" multiple :accept="extensions.map(s => '.' + s).join(',')" @change="choose"/>
  </section>
</template>

<style scoped>
.dropzone { display:flex; align-items:center; gap:15px; padding:21px; background:var(--ks-soft,#f6f8fb); border:1px dashed #a7b6c9; border-radius:6px; color:var(--ks-text,#1f2937) }
.dropzone.over { background:var(--ks-active,#e5eeff); border-color:var(--ks-brand,#2457e6) }
.upload-icon { display:grid; width:45px; height:45px; flex:none; place-items:center; background:var(--ks-surface,#fff); border:1px solid var(--ks-border,#d8e0ea); border-radius:7px; color:var(--ks-deep,#163b85) }
.upload-text { min-width:0 }
.upload-text h3 { margin:0 0 3px; color:var(--ks-strong,#111827); font-size:16px; font-weight:600 }
.dropzone p,.dropzone small { color:var(--ks-secondary,#4b5563); font-size:12px; line-height:1.65 }
.dropzone p { margin:0 }
.dropzone button { display:inline-flex; flex:none; align-items:center; justify-content:center; gap:7px; margin-left:auto; padding:8px 13px; color:white; background:var(--ks-brand,#2457e6); border:1px solid transparent; border-radius:5px; font:inherit; font-size:13px; font-weight:600; cursor:pointer }
.dropzone button:hover { background:var(--ks-deep,#163b85) }
.dropzone button:disabled { opacity:.6; cursor:default }
@media(max-width:650px) { .dropzone { flex-wrap:wrap; padding:16px } .upload-text { flex:1 1 160px } .dropzone button { margin-left:0 } }
</style>
