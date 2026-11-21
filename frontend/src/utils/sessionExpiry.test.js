import test from 'node:test'
import assert from 'node:assert/strict'
import { configureSessionExpiry, notifySessionExpired } from './sessionExpiry.js'

test('401 clears the exact current session once, never a newer login or a forbidden response', () => {
  let token='old',cleared=0,redirects=0
  const dispose=configureSessionExpiry({getToken:()=>token,clearSession:()=>{token='';cleared++},onExpired:()=>redirects++})
  assert.equal(notifySessionExpired('old',403),false)
  assert.equal(notifySessionExpired('old',500),false)
  assert.equal(notifySessionExpired('old','401'),true)
  assert.equal(notifySessionExpired('old',401),false)
  token='new'
  assert.equal(notifySessionExpired('old',401),false)
  assert.equal(token,'new')
  assert.equal(notifySessionExpired('',401),false)
  assert.equal(cleared,1);assert.equal(redirects,1)
  assert.equal(notifySessionExpired('new',401),true)
  assert.equal(redirects,2)
  dispose()
})
