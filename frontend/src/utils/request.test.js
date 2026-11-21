import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { configureSessionExpiry } from './sessionExpiry.js'

const moduleUrl = source => `data:text/javascript;base64,${Buffer.from(source).toString('base64')}`
const source=readFileSync(new URL('./request.js',import.meta.url),'utf8')
  .replace("'axios'",JSON.stringify(import.meta.resolve('axios')))
  .replace("'element-plus'",JSON.stringify(moduleUrl('export const ElMessage={error:m=>globalThis.__authRequestTest.messages.push(m)}')))
  .replace("'@/stores/user'",JSON.stringify(moduleUrl('export const useUserStore=()=>globalThis.__authRequestTest.user')))
  .replace("'./sessionExpiry.js'",JSON.stringify(new URL('./sessionExpiry.js',import.meta.url).href))
const {default:request}=await import(moduleUrl(source))
function setup(t) {
 const state={user:{token:'old'},messages:[],expired:0}
 globalThis.__authRequestTest=state
 const dispose=configureSessionExpiry({getToken:()=>state.user.token,clearSession:()=>{state.user.token=''},onExpired:()=>state.expired++})
 t.after(()=>{dispose();delete globalThis.__authRequestTest})
 return state
}
const businessError=async config=>({config,status:200,data:{code:401,message:'登录失效'},headers:{}})

test('HTTP 200 with business 401 expires once and does not produce repeated generic errors',async t=>{
 const state=setup(t)
 await assert.rejects(request.get('/auth/userInfo',{adapter:businessError}),/登录失效/)
 assert.equal(state.user.token,'');assert.equal(state.expired,1);assert.deepEqual(state.messages,[])
})
test('late HTTP 401 cannot clear a newly logged-in account; 403 also keeps the session',async t=>{
 const state=setup(t)
 await assert.rejects(request.get('/inventory',{adapter:async config=>{
  state.user.token='new'
  throw Object.assign(new Error('expired'),{config,response:{status:401}})
 }}))
 assert.equal(state.user.token,'new');assert.equal(state.expired,0)
 await assert.rejects(request.get('/inventory',{adapter:async config=>{throw Object.assign(new Error('forbidden'),{config,response:{status:403}})}}))
 assert.equal(state.user.token,'new');assert.equal(state.expired,0)
})
test('incorrect password is a login error, and explicit logout credentials are never overwritten',async t=>{
 const state=setup(t)
 await assert.rejects(request.post('/auth/login',{}, {adapter:businessError}),/登录失效/)
 assert.equal(state.user.token,'old');assert.equal(state.expired,0)
 await request.post('/auth/logout',null,{headers:{Authorization:'Bearer explicit-old-token'},adapter:async config=>{
  assert.equal(config.headers.Authorization,'Bearer explicit-old-token')
  return {config,status:200,data:{code:200,data:null},headers:{}}
 }})
 assert.equal(state.user.token,'old')
})
