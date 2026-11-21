import test from 'node:test'
import assert from 'node:assert/strict'
import { consumeTaskEvents } from './taskEventStream.js'
const task='11111111-1111-1111-1111-111111111111'
const event=(sequence,type,legacy)=>({event_id:`event-${sequence}`,task_id:task,conversation_id:'chat',sequence,timestamp:'2026-11-15T00:00:00Z',type,agent_id:null,tool_call_id:null,payload:{legacy}})
const frames=[event(1,'task_started',{type:'run',runId:task}),event(2,'message_started',{type:'snapshot',data:{runRef:task,presentation:'compact_stream'}}),event(3,'message_delta',{type:'delta',text:'中文\n'}),event(4,'message_completed',{type:'result',data:{runRef:task,reply:'中文\n'}}),event(5,'task_completed',{type:'done'})]
const wire=events=>events.map(e=>`id: ${e.event_id}\r\nevent: ${e.type}\r\ndata: ${JSON.stringify(e)}\r\n\r\n`).join('')
function body(text){const bytes=new TextEncoder().encode(text);return new ReadableStream({start(c){for(const b of bytes)c.enqueue(Uint8Array.of(b));c.close()}})}
test('SSE handles every UTF8/CRLF split, deduplicates frames and preserves actual deltas',async()=>{
 const seen=[];const result=await consumeTaskEvents(body(':keepalive\r\n\r\n'+wire([...frames.slice(0,3),frames[2],...frames.slice(3)])),{conversationId:'chat',onEvent:e=>seen.push(e)})
 assert.equal(result.reply,'中文\n');assert.equal(seen.filter(e=>e.type==='delta').length,1)
 assert.equal(seen.find(e=>e.type==='delta').seq,3)
})
test('scope mismatch, lost events and EOF before completion fail instead of accepting partial success',async()=>{
 await assert.rejects(consumeTaskEvents(body(wire(frames)),{conversationId:'other'}),/会话/)
 await assert.rejects(consumeTaskEvents(body(wire([frames[0],frames[2]])),{conversationId:'chat'}),/顺序/)
 await assert.rejects(consumeTaskEvents(body(wire(frames.slice(0,3))),{conversationId:'chat'}),/中断/)
})

test('final message alone is not successful completion and a failed tool/task retains partial text',async()=>{
 const seen=[]
 await assert.rejects(consumeTaskEvents(body(wire(frames.slice(0,4))),{conversationId:'chat',onEvent:e=>seen.push(e)}),/中断/)
 assert.equal(seen.some(e=>e.type==='result'),false)
 const failure=event(4,'task_failed',{type:'error',message:'RUN_TIMED_OUT'})
 failure.payload.code='RUN_TIMED_OUT'
 await assert.rejects(consumeTaskEvents(body(wire([...frames.slice(0,3),failure])),{conversationId:'chat'}),e=>e.taskStatus==='FAILED'&&e.message==='RUN_TIMED_OUT')
})

test('a silent connection times out and cancellation releases the reader',async()=>{
 let cancelled=false
 await assert.rejects(consumeTaskEvents(new ReadableStream({cancel(){cancelled=true}}),{conversationId:'chat',idleTimeoutMs:20}),/无响应/)
 assert.equal(cancelled,true)
 const controller=new AbortController()
 const pending=consumeTaskEvents(new ReadableStream(),{conversationId:'chat',signal:controller.signal})
 controller.abort()
 await assert.rejects(pending,{name:'AbortError'})
})

test('a mismatched typed event cannot forge a result or mark a partial stream complete',async()=>{
 const seen=[]
 const forged=event(2,'heartbeat',{type:'result',data:{reply:'forged'}})
 await assert.rejects(consumeTaskEvents(body(wire([frames[0],forged])),{conversationId:'chat',onEvent:e=>seen.push(e)}),/格式/)
 assert.equal(seen.some(e=>e.type==='result'),false)
})
