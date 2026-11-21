<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useUserStore } from '@/stores/user'
import { createVisionImageSession } from '@/api/visionEvidence'

const props = defineProps({ record: { type: Object, required: true } })
const user = useUserStore()
const session = createVisionImageSession()
const visible = ref(false)
const previews = ref([])
let generation = 0
const entries = computed(() => props.record.imageEntries || [])
const canCompare = computed(() => entries.value.some((image) => ['original', 'annotated'].includes(image.variant) && image.status === 'AVAILABLE'))
const hasInput = computed(() => entries.value.some((image) => image.variant === 'input' && image.status === 'AVAILABLE'))
const title = computed(() => `检验 ${props.record.inspectionNo || props.record.inspectionId || '未知'} · 批次 ${props.record.batchNo || '未绑定'} · ${props.record.recordedAt || '日期未知'}${props.record.mock === true ? ' · 模拟记录' : props.record.mock !== false ? ' · 模拟状态未声明' : ''}`)

function close() {
  generation++
  visible.value = false
  previews.value = []
  session.close()
}
async function open(variants) {
  close()
  const current = generation
  const token = user.token
  visible.value = true
  previews.value = variants.map((variant) => ({ ...entries.value.find((entry) => entry.variant === variant),
    variant, url: '', loading: true, error: '', zoom: false }))
  await Promise.all(previews.value.map(async (preview) => {
    if (preview.status !== 'AVAILABLE') { preview.loading = false; return }
    try {
      const url = await session.load(props.record, preview.variant, token)
      if (current === generation && user.token === token) preview.url = url
    } catch (error) {
      if (current === generation && error.name !== 'AbortError') preview.error = error.message
    } finally {
      if (current === generation) preview.loading = false
    }
  }))
}
watch(() => props.record, close, { deep: true, flush: 'sync' })
watch(() => user.token, close, { flush: 'sync' })
onBeforeUnmount(close)
</script>

<template>
  <section class="vision-images" aria-label="归档图片证据">
    <p v-if="!entries.length">历史记录未保留图片，图像哈希仅用于标识归档来源。</p>
    <template v-else>
      <ul class="image-status"><li v-for="entry in entries" :key="entry.variant">{{ entry.label }}：{{ entry.notice }}<span v-if="entry.width && entry.height"> · {{ entry.width }} × {{ entry.height }}</span></li></ul>
      <p>标注框坐标基于推理输入图尺寸；原图与输入图可能有不同分辨率。</p>
      <div class="image-actions">
        <button v-if="canCompare" type="button" @click="open(['original', 'annotated'])">原图 / 标注图对照</button>
        <button v-if="hasInput" type="button" @click="open(['input'])">查看推理输入图</button>
      </div>
    </template>
    <el-dialog class="aw-vision-dialog" v-if="visible" :model-value="visible" :title="title" width="92%" append-to-body destroy-on-close @update:model-value="close" @close="close">
      <p v-if="record.mock === true" class="image-warning">模拟记录，不计为真实缺陷。</p>
      <p class="image-caption">标注框坐标基于推理输入图尺寸。点击放大后可滚动查看细节。</p>
      <div class="image-comparison" :class="{ single: previews.length === 1 }">
        <section v-for="preview in previews" :key="preview.variant" class="image-pane">
          <h4>{{ preview.label }}<span v-if="preview.width && preview.height"> · {{ preview.width }} × {{ preview.height }}</span></h4>
          <p v-if="preview.loading" role="status">正在读取归档图片…</p>
          <p v-else-if="preview.error" role="alert" class="image-warning">{{ preview.error }}</p>
          <p v-else-if="!preview.url">{{ preview.notice }}</p>
          <template v-else>
            <button type="button" :aria-pressed="preview.zoom" @click="preview.zoom = !preview.zoom">{{ preview.zoom ? '适应窗口' : '放大查看' }}</button>
            <div class="image-scroll" tabindex="0" :aria-label="`${preview.label}，可滚动查看`">
              <img :src="preview.url" :alt="`${title} · ${preview.label}`" :class="{ zoomed: preview.zoom }" @error="preview.error = '图片内容无法显示，请重新查询。'" />
            </div>
          </template>
        </section>
      </div>
      <div class="image-actions close-action"><button type="button" @click="close">关闭图片</button></div>
    </el-dialog>
  </section>
</template>

<style scoped>
.vision-images { margin: 12px 0; color: var(--aw-muted, #62666d); font: 13px/1.7 var(--aw-font-sans, Inter, sans-serif); }
.image-status { padding-left: 20px; line-height: 1.8; }
.image-actions { display: flex; flex-wrap: wrap; gap: 8px; }
button { border: 1px solid var(--aw-border, #e4e4e7); border-radius: 4px; background: var(--aw-panel, #f5f5f5); color: var(--aw-text, #202124); padding: 8px 12px; cursor: pointer; font: 13px var(--aw-font-sans, Inter, sans-serif); }
button:hover { background: var(--aw-hover, #eceef0); }
button:focus-visible, .image-scroll:focus-visible { outline: 2px solid var(--aw-muted, #62666d); outline-offset: 3px; }
.image-comparison { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; color: var(--aw-text, #202124); }
.image-comparison.single { grid-template-columns: minmax(0, 1fr); }
.image-pane { min-width: 0; padding: 12px; border: 1px solid var(--aw-border, #e4e4e7); border-radius: 6px; background: var(--aw-panel, #f5f5f5); font-size: 13px; }
.image-pane h4 { margin: 0 0 12px; font-size: 14px; font-weight: 500; }
.image-pane h4 span, .image-caption { color: var(--aw-muted, #62666d); font-size: 12px; }
.image-scroll { overflow: auto; max-height: 65vh; margin-top: 10px; background: var(--aw-bg, #ffffff); border: 1px solid var(--aw-border, #e4e4e7); border-radius: 4px; }
.image-scroll img { display: block; max-width: 100%; height: auto; margin: auto; }
.image-scroll img.zoomed { max-width: none; width: 200%; }
.image-warning { color: var(--aw-warning, #8c6228); font-size: 12px; }
.close-action { justify-content: flex-end; margin-top: 16px; }
:global(.aw-vision-dialog) { --el-dialog-bg-color: var(--aw-surface, #fafafa); --el-text-color-primary: var(--aw-text, #202124); --el-text-color-regular: var(--aw-muted, #62666d); --el-color-primary: var(--aw-text, #202124); --el-border-radius-base: 6px; --el-dialog-box-shadow: none; border: 1px solid var(--aw-border, #e4e4e7); border-radius: 8px; background: var(--aw-surface, #fafafa); color: var(--aw-text, #202124); font-family: var(--aw-font-sans, Inter, sans-serif); color-scheme: light; box-shadow: none; }
:global(.aw-vision-dialog .el-dialog__title) { font-size: 14px; line-height: 1.7; }
:global(.aw-vision-dialog .el-dialog__header) { padding-right: 32px; }
:global(.aw-vision-dialog .el-dialog__headerbtn:focus-visible) { outline: 2px solid var(--aw-muted, #62666d); outline-offset: -4px; }
@media (max-width: 700px) { .image-comparison { grid-template-columns: minmax(0, 1fr); } }
</style>
