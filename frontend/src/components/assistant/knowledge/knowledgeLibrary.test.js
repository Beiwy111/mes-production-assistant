import test from 'node:test'
import assert from 'node:assert/strict'
import { jobEvidence, mergeKnowledgeGraphs, readUploadedLibrary } from './knowledgeLibrary.js'

const job = id => ({ id,filename:id+'.pdf',status:'completed',counts:{chunks:40,entities:0,relations:0},
  chunks:Array.from({length:40},(_,i)=>({id:'chunk'+i,text:'Actual source '+i,location:'page:1'})),entities:[],relations:[] })
test('all completed uploads are loaded without the old canvas limit; same chunk IDs stay job-scoped', async () => {
  let active=0, maximum=0
  const jobs=Array.from({length:8},(_,i)=>job(String(i)))
  const request=async (token,path)=>{
    assert.equal(token,'session')
    if(path==='/jobs')return {jobs:[...jobs,{id:'pending',status:'running'}]}
    active++;maximum=Math.max(maximum,active)
    await new Promise(resolve=>setTimeout(resolve,2));active--
    return jobs.find(j=>path==='/jobs/'+j.id)
  }
  const result=await readUploadedLibrary('session',new AbortController().signal,request)
  assert.equal(result.graph.nodes.length,328)
  assert.equal(result.graph.edges.length,320)
  assert.equal(maximum,3)
  assert.equal(mergeKnowledgeGraphs(result.graph,result.graph).nodes.length,328)
})
test('incomplete or changed uploads cannot appear as indexed nodes',async()=>{
  assert.throws(()=>jobEvidence({...job('a'),status:'running'}),/尚未完成/)
  assert.throws(()=>jobEvidence({...job('a'),chunks:[]}),/完整性/)
  assert.throws(()=>jobEvidence({...job('a'),entities:[{id:'x'}]}),/完整性/)
  await assert.rejects(readUploadedLibrary('session',undefined,async (_,path)=>{
    if(path==='/jobs')return {jobs:[job('a')]}
    throw new Error('403')
  }),/403/)
})
test('in-progress preview contains only returned chunks, never queued fake nodes',()=>{
  assert.equal(jobEvidence({id:'a',status:'queued'},false).hits.length,0)
  assert.equal(jobEvidence({...job('a'),status:'running'},false).hits.length,40)
})
