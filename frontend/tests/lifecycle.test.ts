import assert from 'node:assert/strict'
import test from 'node:test'
import { bindStageLifecycle } from '../src/stage/lifecycle.ts'

test('ordinary hidden/visible switching does not disconnect; freeze and pagehide do, with removable listeners', () => {
  const page = Object.assign(new EventTarget(), { hidden: false }), browser = new EventTarget()
  const events: string[] = []
  const dispose = bindStageLifecycle(page, browser, { resume: () => events.push('verify'), freeze: () => events.push('freeze'), leave: () => events.push('leave') })
  page.hidden = true; page.dispatchEvent(new Event('visibilitychange')); assert.deepEqual(events, [])
  page.hidden = false; page.dispatchEvent(new Event('visibilitychange')); assert.deepEqual(events, ['verify'])
  page.dispatchEvent(new Event('freeze')); browser.dispatchEvent(new Event('pagehide')); browser.dispatchEvent(new Event('pageshow'))
  assert.deepEqual(events, ['verify', 'freeze', 'leave', 'verify'])
  dispose(); page.dispatchEvent(new Event('freeze')); browser.dispatchEvent(new Event('pagehide')); assert.equal(events.length, 4)
})
