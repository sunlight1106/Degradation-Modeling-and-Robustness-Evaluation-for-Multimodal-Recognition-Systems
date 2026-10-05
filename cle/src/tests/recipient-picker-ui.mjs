// Synthetic users and HTTP responses only; no messages are sent.
import { createApp, h, nextTick, ref } from 'vue'
import MessageRecipientPicker from '../components/MessageRecipientPicker.vue'

const fixture = document.getElementById('fixture'), passed = [], picked = ref([])
const assert = (ok, label) => { if (!ok) throw new Error(label); passed.push(label) }
const wait = async predicate => { for (let i = 0; i < 160; i++) { await new Promise(resolve => setTimeout(resolve, 5)); await nextTick(); if (predicate()) return } throw new Error('UI wait timed out') }
const users = Array.from({ length: 60 }, (_, i) => ({ id: i + 1, displayName: '同名用户', username: `user${i + 1}`, identityCode: `PKB-${String(i + 1).padStart(32, '0')}`, relationship: 'USER' }))
const envelope = data => new Response(JSON.stringify({ success: true, data }), { headers: { 'Content-Type': 'application/json' } })
let delayed, failNext = false, app
const requests = []
window.fetch = async url => {
  const params = new URL(url, 'http://synthetic.test').searchParams, q = params.get('q') || '', page = Number(params.get('page') || 0)
  requests.push({ q, page })
  if (q === 'slow') return new Promise(resolve => { delayed = () => resolve(envelope([users[59]])) })
  if (failNext) { failNext = false; throw new Error('Synthetic network failure') }
  const result = users.filter(user => !q || user.identityCode.toLowerCase() === q.toLowerCase() || user.username === q)
  return envelope(result.slice(page * 25, (page + 1) * 25))
}
const input = () => fixture.querySelector('[role=combobox]')
const options = () => [...fixture.querySelectorAll('[role=option]')]
const type = value => { input().value = value; input().dispatchEvent(new Event('input', { bubbles: true })) }
const key = value => input().dispatchEvent(new window.KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true }))
try {
  app = createApp({ setup: () => () => h(MessageRecipientPicker, { modelValue: picked.value, 'onUpdate:modelValue': value => { picked.value = value } }) })
  app.mount(fixture); await nextTick()
  assert(!fixture.querySelector('[role=listbox]'), 'Dropdown starts collapsed instead of displaying all users')
  input().focus(); await wait(() => options().length === 25)
  assert(options().every(option => option.textContent.includes('PKB-')), 'Every duplicate name has a visible complete identity code')
  fixture.querySelector('.recipient-more').click(); await wait(() => options().length === 50)
  fixture.querySelector('.recipient-more').click(); await wait(() => options().length === 60)
  assert(requests.map(request => request.page).join(',') === '0,1,2' && !fixture.querySelector('.recipient-more'), 'Large directories load in pages without duplicate options')
  key('ArrowDown'); key('Enter'); await nextTick()
  assert(picked.value[0]?.id === 1 && input().getAttribute('aria-activedescendant'), 'Keyboard selection targets the active unique user')
  for (const option of options().slice(1, 20)) { option.click(); await nextTick() }
  assert(picked.value.length === 20 && options()[20].disabled && !options()[0].disabled, 'Selection limit disables new recipients but permits deselection')
  assert(fixture.querySelectorAll('.recipient-chip code').length === 20, 'Selected recipients retain their complete identity codes')
  type(users[0].identityCode.toLowerCase()); await wait(() => options().length === 1)
  assert(picked.value.length === 20 && options()[0].getAttribute('aria-selected') === 'true', 'Searching by code preserves selections across pages')
  fixture.querySelector('.recipient-chip button').click(); await nextTick()
  assert(picked.value.length === 19 && !picked.value.some(user => user.id === 1), 'Removing one duplicate name only removes its unique identity')
  key('Escape'); await nextTick()
  assert(!fixture.querySelector('[role=listbox]') && document.activeElement === input(), 'Escape closes dropdown and keeps input focus')
  input().click(); await wait(() => options().length === 1)
  document.body.dispatchEvent(new window.Event('pointerdown', { bubbles: true })); await nextTick()
  assert(!fixture.querySelector('[role=listbox]'), 'Clicking outside closes the dropdown')
  input().click(); await nextTick(); type('slow'); await wait(() => delayed)
  type(users[1].identityCode); await wait(() => options().length === 1 && options()[0].textContent.includes(users[1].identityCode))
  delayed(); await new Promise(resolve => setTimeout(resolve, 15)); await nextTick()
  assert(options()[0].textContent.includes(users[1].identityCode), 'A late search response cannot overwrite newer results')
  failNext = true; type('user3'); await wait(() => fixture.querySelector('[role=alert]'))
  assert(picked.value.length === 19, 'Search errors retain selected recipients')
  fixture.querySelector('[role=alert] button').click(); await wait(() => options().length === 1)
  assert(options()[0].textContent.includes(users[2].identityCode), 'Failed search can be retried')
  delayed = null; type('slow'); await wait(() => delayed); app.unmount(); app = null; delayed(); await nextTick()
  assert(!fixture.querySelector('[role=option]'), 'Unmounted account state cannot be restored by a delayed response')
  document.documentElement.dataset.result = 'passed'
  document.getElementById('results').textContent = `PASS ${passed.length}\n${passed.join('\n')}`
} catch (error) {
  document.documentElement.dataset.result = 'failed'
  document.getElementById('results').textContent = `FAIL after ${passed.length}: ${error.stack}`
} finally { app?.unmount() }
