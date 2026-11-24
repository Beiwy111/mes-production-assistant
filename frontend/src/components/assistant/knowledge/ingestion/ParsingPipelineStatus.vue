<script setup>
import { Check, CircleAlert, Clock3, LoaderCircle } from 'lucide-vue-next'
import { displayDate, stageStates } from './documentPresentation'
defineProps({ job: { type:Object, required:true } })
const labels = {
  upload: '文件上传', parse: '文档解析', chunk: 'Chunk 切分', embedding: 'Embedding 向量化',
  vector: '向量入库', entities: '实体抽取', relations: '关系构建', graph: '图谱入库', complete: '完成入库',
}
function elapsed(milliseconds) {
  if (!Number.isFinite(milliseconds)) return ''
  return milliseconds < 1000 ? milliseconds + ' ms' : (milliseconds / 1000).toFixed(1) + ' s'
}
</script>

<template>
  <section class="trace">
    <ol v-if="job.stages?.length" class="pipeline" aria-label="入库阶段">
      <li v-for="stage in job.stages" :key="stage.key" :class="stage.status" :title="stage.startedAt ? '开始时间 ' + displayDate(stage.startedAt) : undefined">
        <span class="step-icon" aria-hidden="true">
          <Check v-if="stage.status === 'success'"/><CircleAlert v-else-if="stage.status === 'failed'"/>
          <LoaderCircle v-else-if="stage.status === 'running'"/><Clock3 v-else/>
        </span>
        <span class="step-name">{{ labels[stage.key] || stage.key }}</span>
        <small class="result">{{ job.status === 'failed' && stage.status === 'pending' ? '未执行' : stageStates[stage.status] || stage.status }}
          <template v-if="['success','failed'].includes(stage.status) && elapsed(stage.durationMs)"> · {{ elapsed(stage.durationMs) }}</template>
        </small>
        <div v-if="stage.key === 'parse'" class="extraction">
          <span>文本提取 / OCR</span>
          <template v-if="job.metadata?.sections != null">
            <span>提取 {{ job.metadata.sections }} 段<span v-if="job.metadata.characters != null"> · {{ job.metadata.characters.toLocaleString() }} 字</span></span>
            <span v-if="job.metadata.ocrSections != null">{{ job.metadata.ocrSections ? 'OCR 识别 ' + job.metadata.ocrSections + ' 段' : '未使用 OCR' }}</span>
          </template>
          <span v-else>{{ stage.status === 'running' ? '解析中' : stage.status === 'pending' ? '等待解析' : '暂无提取记录' }}</span>
        </div>
        <p v-if="stage.error" class="failure" role="alert">{{ stage.error }}</p>
      </li>
    </ol>
    <p v-else class="no-log">暂无阶段日志。</p>
    <p v-if="job.error && !job.stages?.some(stage => stage.error === job.error)" role="alert" class="failure">{{ job.error }}</p>
    <div v-if="job.updatedAt" class="updated"><Clock3 :size="13"/>最近更新 <time :datetime="job.updatedAt">{{ displayDate(job.updatedAt) }}</time></div>
  </section>
</template>

<style scoped>
.pipeline { margin:0; padding:0; list-style:none }
.pipeline li { position:relative; display:grid; grid-template-columns:20px 1fr auto; align-items:center; gap:4px 8px; padding:6px 0 9px; font-size:13px; color:var(--ks-text,#1f2937) }
.pipeline li:not(:last-child)::after { content:''; position:absolute; top:26px; bottom:-6px; left:9px; border-left:1px solid var(--ks-border,#d8e0ea) }
.step-icon { width:19px; height:19px; display:grid; place-items:center; border-radius:50%; background:#f0f2f4; color:#687386 }
.step-icon svg { width:12px; height:12px; stroke-width:2.25 }
.success .step-icon { background:#e3f0ea; color:#14674e }
.failed .step-icon { background:#fce9e6; color:#a82c26 }
.running .step-icon { background:var(--ks-active,#e5eeff); color:var(--ks-brand,#2457e6) }
.running .step-name { font-weight:600 }
.result { color:var(--ks-secondary,#4b5563); font-size:12px; font-variant-numeric:tabular-nums }
.extraction,.failure { grid-column:2/-1; font-size:12px; line-height:1.55 }
.extraction { display:flex; flex-wrap:wrap; gap:2px 8px; padding:5px 8px; margin-top:2px; background:var(--ks-soft,#f6f8fb); color:var(--ks-secondary,#4b5563); border-radius:3px }
.extraction>span:first-child { width:100%; font-weight:600 }
.failure { margin:3px 0; color:#a82c26; overflow-wrap:anywhere }
.no-log,.updated { color:var(--ks-secondary,#4b5563); font-size:12px }
.updated { display:flex; flex-wrap:wrap; align-items:center; gap:5px; padding-top:11px; border-top:1px solid var(--ks-border,#d8e0ea) }
@media(prefers-reduced-motion:no-preference) { .running .step-icon svg { animation:turn 1.5s linear infinite } }
@keyframes turn { to { transform:rotate(360deg) } }
</style>
