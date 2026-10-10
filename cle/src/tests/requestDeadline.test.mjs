import test from 'node:test'
import assert from 'node:assert/strict'
import { RequestTimeoutError, withRequestDeadline } from '../lib/requestDeadline.ts'

test('completed requests return their value without aborting the transport', async () => {
  let signal
  const value = await withRequestDeadline(undefined, 20, async current => { signal = current; return 42 })
  await new Promise(resolve => setTimeout(resolve, 30))
  assert.equal(value, 42)
  assert.equal(signal.aborted, false)
})

test('a stalled request times out even when the transport ignores cancellation', async () => {
  let signal
  await assert.rejects(withRequestDeadline(undefined, 20, current => {
    signal = current
    return new Promise(() => {})
  }), RequestTimeoutError)
  assert.equal(signal.aborted, true)
})

test('the deadline includes reading the response body', async () => {
  await assert.rejects(withRequestDeadline(undefined, 20, async () => {
    const response = { json: () => new Promise(() => {}) }
    return await response.json()
  }), RequestTimeoutError)
})

test('caller cancellation keeps its reason and aborts the nested transport', async () => {
  const caller = new AbortController(), reason = new Error('User cancelled')
  let signal
  const pending = withRequestDeadline(caller.signal, 1000, current => {
    signal = current
    return new Promise(() => {})
  })
  caller.abort(reason)
  await assert.rejects(pending, error => error === reason)
  assert.equal(signal.aborted, true)
})

test('an already cancelled caller does not start any work', async () => {
  const caller = new AbortController(), reason = new Error('Navigation cancelled')
  caller.abort(reason)
  let calls = 0
  await assert.rejects(withRequestDeadline(caller.signal, 20, async () => { calls++ }), error => error === reason)
  assert.equal(calls, 0)
})

test('ordinary failures are returned immediately and not replaced by timeout errors', async () => {
  const reason = new Error('Network unavailable')
  await assert.rejects(withRequestDeadline(undefined, 20, async () => { throw reason }), error => error === reason)
})
