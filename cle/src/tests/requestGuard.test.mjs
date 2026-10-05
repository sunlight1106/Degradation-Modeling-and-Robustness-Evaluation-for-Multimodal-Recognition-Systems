import test from 'node:test'
import assert from 'node:assert/strict'
import { createRequestGuard, isPreviewExpired } from '../lib/requestGuard.ts'

test('starting a newer request aborts and invalidates the previous response', () => {
  const guard = createRequestGuard()
  const first = guard.start()
  const second = guard.start()
  assert.equal(first.signal.aborted, true)
  assert.equal(first.current(), false)
  assert.equal(second.current(), true)
})
test('cancel and navigation discard late results even after another request starts', () => {
  const guard = createRequestGuard()
  const first = guard.start()
  guard.cancel()
  assert.equal(first.current(), false)
  assert.equal(first.signal.aborted, true)
  assert.equal(guard.start().current(), true)
  assert.equal(first.current(), false)
})
test('expiry is fail closed and boundary timestamps cannot execute', () => {
  const now = Date.parse('2026-10-02T11:00:00Z')
  assert.equal(isPreviewExpired('not a date', now), true)
  assert.equal(isPreviewExpired('2026-10-02T11:00:00Z', now), true)
  assert.equal(isPreviewExpired('2026-10-02T10:59:59Z', now), true)
  assert.equal(isPreviewExpired('2026-10-02T11:00:01Z', now), false)
})
