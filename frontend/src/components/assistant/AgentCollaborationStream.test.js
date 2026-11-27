import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { renderToString } from '@vue/server-renderer'
import * as timeline from '../../utils/collaborationTimeline.js'
import * as inspector from '../../utils/agentInspector.js'

const { descriptor } = parse(readFileSync(new URL('./AgentCollaborationStream.vue', import.meta.url), 'utf8'))
const dependencies = { vue, '@/utils/collaborationTimeline': timeline, '@/utils/agentInspector': inspector,
  './AgentAvatar.vue': { props:['agentId'], setup:p=>()=>vue.h('span',{'data-avatar':p.agentId}) },
  './AgentStatusIndicator.vue': { props:['status'], setup:p=>()=>vue.h('span',{'data-state':p.status}) },
  'lucide-vue-next': Object.fromEntries(['ChevronRight','Wrench','ArrowRightLeft'].map(name=>[name,()=>null])) }
const code = compileScript(descriptor,{id:'inline-flow-test',inlineTemplate:true}).content
  .replace(/^import\s*\{([^}]+)\}\s*from\s*['"]([^'"]+)['"];?\s*$/gm,(_,bindings,from)=>`const {${bindings.replace(/\bas\b/g,':')}}=dependencies[${JSON.stringify(from)}];`)
  .replace(/^import\s+(\w+)\s+from\s*['"]([^'"]+)['"];?\s*$/gm,(_,binding,from)=>`const ${binding}=dependencies[${JSON.stringify(from)}];`)
  .replace('export default','return')
const component = new Function('dependencies',code)(dependencies)
const render = message=>renderToString(vue.createSSRApp(component,{message}))

test('live production collaboration opens before the answer and renders only observed safe content',async()=>{
  const message={status:'RUNNING',productionFlow:{status:'PREPARING'},agents:[],workflow:{events:[
    {seq:1,agentId:'PRODUCTION',toolCallId:'read',toolId:'TASK_ORDERCONTEXT',kind:'STEP_STARTED',status:'RUNNING',label:'核对订单',detail:'查询 <CO123>'},
  ]}}
  const html=await render(message)
  assert.match(html, /<details[^>]*\sopen/)
  assert.match(html, /data-avatar="PRODUCTION"/)
  assert.match(html, /查询 &lt;CO123&gt;/)
  assert.doesNotMatch(html, /质量检验|售后追溯|已完成/)
  message.status='CANCELLED'
  const stopped=await render(message)
  assert.doesNotMatch(stopped, /<details[^>]*\sopen/)
  assert.match(stopped, /已取消/)
})
test('ordinary API answers do not create collaboration and completed records stay collapsed',async()=>{
  assert.doesNotMatch(await render({status:'RUNNING',directChat:true}), /<details/)
  const completed=await render({status:'COMPLETED',agents:[],workflow:{events:[
    {seq:1,agentId:'ORDER',kind:'STEP_COMPLETED',status:'COMPLETED',label:'订单查询',detail:'查到4条记录'},
  ]}})
  assert.match(completed,/查看协作过程/)
  assert.doesNotMatch(completed,/<details[^>]*\sopen/)
  assert.match(completed,/查到4条记录/)
})
