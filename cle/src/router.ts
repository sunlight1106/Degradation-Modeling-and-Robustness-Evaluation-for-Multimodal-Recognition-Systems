import { createRouter, createWebHistory } from 'vue-router'
import { authStore } from '@/stores/auth'
import HomeView from '@/views/HomeView.vue'
import LoginView from '@/views/LoginView.vue'
import AppShell from '@/components/AppShell.vue'

const router = createRouter({
  history: createWebHistory(),
  scrollBehavior: to => to.hash ? { el: to.hash, behavior: 'smooth', top: 88 } : { top: 0 },
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/register', name: 'register', component: () => import('@/views/RegisterView.vue') },
    { path: '/models', name: 'models', component: () => import('@/views/ModelsView.vue') },
    { path: '/docs', name: 'docs', component: () => import('@/views/DocsView.vue') },
    { path: '/pay/:token', name: 'payment-scan', component: () => import('@/views/PaymentScanView.vue') },
    // 分享只读页：要求登录，但不要求特定权限（分享的意义就是跨用户可见）
    {
      path: '/shared/:token',
      name: 'shared-note',
      component: () => import('@/views/SharedNoteView.vue'),
      meta: { requiresAuth: true },
    },
    {
      path: '/app',
      component: AppShell,
      meta: { requiresAuth: true },
      children: [
        { path: '', redirect: '/app/home' },
        { path: 'home', name: 'app-home', component: () => import('@/views/OverviewView.vue'), meta: { permission: 'dashboard:read' } },
        { path: 'knowledge', name: 'knowledge', component: () => import('@/views/KnowledgeView.vue'), meta: { permission: 'knowledge:read' } },
        { path: 'notes', name: 'notes', component: () => import('@/views/NotesView.vue'), meta: { permission: 'note:read' } },
        { path: 'notes/new', name: 'note-create', component: () => import('@/views/NoteEditorView.vue'), meta: { permission: 'note:write' } },
        { path: 'notes/:id/edit', name: 'note-edit', component: () => import('@/views/NoteEditorView.vue'), meta: { permission: 'note:write' } },
        { path: 'overview', redirect: '/app/home' },
        { path: 'upload', name: 'upload', component: () => import('@/views/UploadView.vue'), meta: { permission: 'experiment:run' } },
        { path: 'comparisons', name: 'comparisons', component: () => import('@/views/ComparisonsView.vue'), meta: { permissions: ['experiment:read', 'experiment:read:any'] } },
        { path: 'models', name: 'app-models', component: () => import('@/views/ModelsView.vue'), meta: { permission: 'model:read' } },
        { path: 'images', name: 'images', component: () => import('@/views/ImagesView.vue'), meta: { permissions: ['file:read', 'file:read:any'] } },
        { path: 'logs', name: 'logs', component: () => import('@/views/LogsView.vue'), meta: { permissions: ['experiment:read', 'experiment:read:any'] } },
        { path: 'downloads', name: 'downloads', component: () => import('@/views/DownloadsView.vue'), meta: { permissions: ['file:read', 'file:read:any', 'experiment:read', 'experiment:read:any'] } },
        { path: 'billing', name: 'billing', component: () => import('@/views/BillingView.vue'), meta: { permission: 'billing:read' } },
        { path: 'mail', name: 'mail', component: () => import('@/views/MailboxView.vue'), meta: { permission: 'message:read' } },
        { path: 'users', name: 'users', component: () => import('@/views/UsersView.vue'), meta: { permission: 'user:read' } },
        { path: 'roles', name: 'roles', component: () => import('@/views/RolesView.vue'), meta: { permission: 'role:read' } },
        { path: 'docs', redirect: '/docs' },
        { path: 'settings', name: 'settings', component: () => import('@/views/SettingsView.vue') },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async to => {
  if (!to.matched.some(record => record.meta.requiresAuth)) return true
  const user = await authStore.ensureUser()
  if (!user) return { name: 'login', query: { redirect: to.fullPath } }
  const permission = to.meta.permission as string | undefined
  const permissions = to.meta.permissions as string[] | undefined
  if (permission && !authStore.has(permission)) return { path: '/app/settings' }
  if (permissions && !authStore.hasAny(...permissions)) return { path: '/app/settings' }
  return true
})

export default router
