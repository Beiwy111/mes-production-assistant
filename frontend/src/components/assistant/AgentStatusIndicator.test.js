import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { stripTypeScriptTypes } from 'node:module'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as icons from 'lucide-vue-next'
import { renderToString } from '@vue/server-renderer'
const {descriptor}=parse(readFileSync(new URL('./AgentStatusIndicator.vue',import.meta.url),'utf8'))
const code=stripTypeScriptTypes(compileScript(descriptor,{id:'indicator-test',inlineTemplate:true}).content)
 .replace(/^import\s*\{([^}]+)\}\s*from\s*['"]([^'"]+)['"];?\s*$/gm,(_,bindings,from)=>`const {${bindings.replace(/\bas\b/g,':')}} = deps[${JSON.stringify(from)}];`)
 .replace('export default','return')
const component=new Function('deps',code)({vue,'lucide-vue-next':icons})
test('only a live running status gets a rotating arc; finished, queued and historic starts stay still',async()=>{
 for(const status of ['RUNNING','COMPLETED','FAILED','UNCALLED','WAITING','CANCELLED']){
  const html=await renderToString(vue.createSSRApp(component,{status}))
  assert.equal(html.includes('status-ring rotating'),status==='RUNNING',status)
  assert.ok(html.includes(`data-status="${status}"`))
  if(status==='COMPLETED')assert.match(html,/lucide-check/)
  if(status==='FAILED')assert.match(html,/lucide-circle-alert/)
 }
 const historical=await renderToString(vue.createSSRApp(component,{status:'RUNNING',animate:false}))
 assert.doesNotMatch(historical,/rotating/)
})
