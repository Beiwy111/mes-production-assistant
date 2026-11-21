import test from 'node:test'
import assert from 'node:assert/strict'
import { createTextStreamBuffer } from './textStreamBuffer.js'

function harness() {
 let now=0,id=0;const frames=new Map(),seen=[]
 const buffer=createTextStreamBuffer({request:fn=>{frames.set(++id,fn);return id},cancel:key=>frames.delete(key),now:()=>now,onChange:(text,pending)=>seen.push({text,pending,now})})
 return {buffer,seen,frames,tick(ms=22){now+=ms;const callbacks=[...frames.values()];frames.clear();callbacks.forEach(fn=>fn(now))}}
}
test('native bursts display progressively and completion drains within240ms without losing characters',()=>{
 const h=harness(),text='已查询到以下库存信息：'
 h.buffer.update(text,{live:true});assert.equal(h.seen.at(-1)?.text||'','')
 h.tick();h.tick();assert.ok(h.seen.at(-1).text.length>0&&h.seen.at(-1).text.length<text.length)
 h.buffer.update(text,{live:false});for(let i=0;i<12;i++)h.tick()
 assert.equal(h.seen.at(-1).text,text);assert.equal(h.frames.size,0);assert.equal(h.seen.at(-1).pending,false)
})
test('graphemes split across deltas never expose broken emoji or accents',()=>{
 const h=harness()
 for(const text of ['中👩','中👩‍','中👩‍💻','中👩‍💻e','中👩‍💻e\u0301','中👩‍💻e\u0301文']) {
  h.buffer.update(text,{live:true});h.tick(100)
 }
 h.buffer.update('中👩‍💻e\u0301文',{live:false});h.tick(240)
 assert.equal(h.seen.at(-1).text,'中👩‍💻e\u0301文')
 for(const {text} of h.seen)assert.ok(['','中','中👩‍💻','中👩‍💻e\u0301','中👩‍💻e\u0301文'].includes(text),text)
})
test('large queues accelerate, and stop/unmount/reduced motion do not leave background work',()=>{
 const h=harness(),text='长文字'.repeat(1000)
 h.buffer.update(text,{live:true});h.tick(22);h.tick(22)
 assert.ok(h.seen.at(-1).text.length>1)
 const shown=h.seen.at(-1).text
 h.buffer.update(text,{live:false,stopped:true});h.tick(1000)
 assert.equal(h.seen.at(-1).text,shown);assert.equal(h.frames.size,0)
 const reduced=harness();reduced.buffer.update(text,{live:true,reduced:true})
 assert.equal(reduced.seen.at(-1).text,text);assert.equal(reduced.frames.size,0)
 const disposed=harness();disposed.buffer.update(text,{live:true});disposed.buffer.dispose();assert.equal(disposed.frames.size,0)
})
test('history is immediate, separate queues cannot mix and an authoritative replacement clears old text',()=>{
 const a=harness(),b=harness();a.buffer.update('历史记录',{live:false});assert.equal(a.seen.at(-1).text,'历史记录')
 b.buffer.update('新生成文字',{live:true});b.tick();assert.notEqual(b.seen.at(-1)?.text,'历史记录')
 b.buffer.update('证据已失效',{live:false,replace:true});assert.equal(b.seen.at(-1).text,'证据已失效')
 a.buffer.dispose();b.buffer.dispose();assert.equal(a.frames.size+b.frames.size,0)
})

test('read-only recovery after interruption releases the frozen display to the authoritative result',()=>{
 const h=harness();h.buffer.update('已生成的部分文本',{live:true});h.tick(44)
 h.buffer.update('已生成的部分文本',{live:false,stopped:true})
 const prefix=h.seen.at(-1).text
 h.buffer.update('已生成的部分文本及服务端恢复的结论',{live:false,stopped:false})
 assert.notEqual(h.seen.at(-1).text,prefix)
 assert.equal(h.seen.at(-1).text,'已生成的部分文本及服务端恢复的结论')
 assert.equal(h.frames.size,0)
})
