import { createApp, nextTick } from 'vue'
import { createRouter, createMemoryHistory } from 'vue-router'
import { adminApi } from '../api/admin'
import { authStore } from '../stores/auth'
import AdminTrendChart from '../components/AdminTrendChart.vue'
import AdminUserDetail from '../components/AdminUserDetail.vue'
import AdministrationView from '../views/AdministrationView.vue'
const fixture = document.getElementById('fixture'), checks = []
const assert = (value, message) => { if (!value) throw new Error(message); checks.push(message) }
const wait = async predicate => { for (let i = 0; i < 100; i++) { await new Promise(r => setTimeout(r, 5)); await nextTick(); if (predicate()) return } throw new Error('UI did not settle') }
const button = text => [...fixture.querySelectorAll('button')].find(el => el.textContent.includes(text))
const change = async (element, value) => { element.value = value; element.dispatchEvent(new Event('input', { bubbles: true })); element.dispatchEvent(new Event('change', { bubbles: true })); await nextTick() }
const days = [{ date:'2026-10-09',registrations:2,loginUsers:3,notes:4,aiCalls:5,experiments:6 },{ date:'2026-10-10',registrations:1,loginUsers:4,notes:2,aiCalls:0,experiments:1 }]
const original = { ...adminApi }
let app
try {
  authStore.state.user = { id:1,roleCode:'ADMIN',permissions:[] }
  assert(authStore.has('admin:stats') && authStore.has('training:use'), 'ADMIN can reach new modules even with an older client permission list')
  app = createApp(AdminTrendChart, { days }); app.mount(fixture)
  assert(fixture.querySelectorAll('polyline').length === 2, 'Chart starts with two clear series')
  button('新建笔记').click(); await nextTick(); assert(fixture.querySelectorAll('polyline').length === 3, 'Legend toggles actual series')
  const point = fixture.querySelector('g[tabindex]'); point.dispatchEvent(new Event('mouseenter')); await nextTick()
  assert(fixture.querySelector('.chart-readout').textContent.includes('2026-10-09'), 'Hover exposes the selected date')
  fixture.querySelector('svg').dispatchEvent(new Event('mouseleave')); await nextTick()
  assert(!fixture.querySelector('.chart-cursor'), 'Moving away clears the highlight')
  button('查看数据表').click(); await nextTick(); assert(fixture.querySelectorAll('tbody tr').length === 2, 'Readable data table matches each day')
  app.unmount()
  const catalog = [{ code:'vocabulary:use',label:'背单词',group:'学习' },{ code:'contacts:use',label:'联系人',group:'协作' }]
  const member = { id:2,identityCode:'PKB-SYNTHETIC',username:'member',displayName:'Member',email:'member@example.test',roleCode:'RESEARCHER',roleId:2,status:'ACTIVE',createdAt:'2026-10-01T00:00:00Z' }
  let access = { rolePermissions:['vocabulary:use'], grants:['contacts:use'], denies:['vocabulary:use'], effectivePermissions:['contacts:use'], expiresAt:null }, sent, saveCount = 0
  adminApi.detail = async id => { assert(id === 2, 'Detail requests remain bound to the selected account'); return { user:member, access,notes:2,files:3,storageBytes:1024,aiCalls:4,experiments:5,activeSessions:1,lastLoginAt:null } }
  adminApi.access = async (id, payload) => { saveCount++; sent = { id, ...payload }; access = {...access, ...payload}; return access }
  const proto = Object.getPrototypeOf(document.createElement('dialog')); proto.showModal = function() { this.setAttribute('open','') }; proto.close = function() { this.removeAttribute('open') }
  app = createApp(AdminUserDetail, { id:2,roles:[],permissions:catalog }); app.mount(fixture); await wait(() => fixture.querySelector('.individual-permissions'))
  assert(fixture.querySelector('select[aria-label=背单词]').value === 'deny', 'Individual deny is shown separately from role defaults')
  await change(fixture.querySelector('select[aria-label=背单词]'), 'allow')
  fixture.querySelectorAll('form')[1].dispatchEvent(new Event('submit', { bubbles:true,cancelable:true })); await wait(() => fixture.querySelector('[role=status]')?.textContent.includes('已保存'))
  assert(saveCount === 1 && sent.id === 2 && sent.grants.includes('vocabulary:use') && !sent.denies.length, 'Saving sends one explicit override update for the chosen user')
  button('全部恢复跟随角色').click(); await nextTick(); fixture.querySelectorAll('form')[1].dispatchEvent(new Event('submit', { bubbles:true,cancelable:true })); await wait(() => saveCount === 2 && fixture.querySelector('[role=status]'))
  assert(sent.grants.length === 0 && sent.denies.length === 0 && sent.expiresAt === null, 'Restoring role defaults clears overrides without creating an expiry')
  app.unmount()
  authStore.state.user = { id:3,roleCode:'AUDITOR',permissions:['admin:stats'] }
  let auditCalls = 0, ranges = []
  adminApi.statistics = async range => { ranges.push(range); return { users:2,enabledUsers:2,disabledUsers:0,expiredUsers:0,recentUsers:1,roles:2,notes:4,files:3,storageBytes:1024,aiCalls:5,experiments:6,days,zone:'UTC',generatedAt:'2026-10-10T00:00:00Z' } }
  adminApi.audit = async () => { auditCalls++; throw new Error('Auditor is not authorized for audit logs') }
  const router = createRouter({ history:createMemoryHistory(),routes:[{path:'/',component:{template:'<div />'}}] })
  app = createApp(AdministrationView); app.use(router); app.mount(fixture); await wait(() => fixture.querySelector('.admin-metrics'))
  assert(auditCalls === 0 && !fixture.textContent.includes('管理操作记录'), 'Delegated statistics access does not fetch unauthorized audit logs')
  await change(fixture.querySelector('select'), '7'); await wait(() => ranges.includes(7))
  assert(ranges.includes(7), 'Date range selector requests the selected server range')
  document.documentElement.dataset.result = 'passed'; document.getElementById('results').textContent = `PASS (${checks.length} assertions)\n${checks.join('\n')}`
} catch (error) { document.documentElement.dataset.result = 'failed'; document.getElementById('results').textContent = error.stack; throw error }
finally { app?.unmount(); Object.assign(adminApi, original); authStore.state.user = null }
