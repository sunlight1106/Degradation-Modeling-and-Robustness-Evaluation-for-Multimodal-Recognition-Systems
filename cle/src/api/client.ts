import type {
  ApiEnvelope,
  DashboardSummary,
  BillingSummary,
  FileView,
  InferenceView,
  LoginResponse,
  ModelView,
  ModelRuntimeView,
  PaymentMethod,
  ProviderBudgetView,
  RechargeOrderView,
  PermissionView,
  RoleView,
  TaskType,
  UserStatus,
  UserView,
  AdminWalletView,
  ProviderCredentialView,
  WorkspaceView,
  WorkspaceMemberRole,
  UserDirectoryView,
  MessageContactView,
  MessageView,
  PublicPaymentView,
  KnowledgeTopicView,
  KnowledgeEntryView,
  NoteView,
  NoteSummaryView,
  NoteAssistAction,
  NoteAssistResponse,
  NoteExportFormat,
  NoteReferenceType,
  NoteShareView,
  NoteStatusCode,
  SharedNoteView,
} from '@/types/api'

const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api/v1'
const TOKEN_KEY = 'personal_platform_token'
const LEGACY_TOKEN_KEY = 'robustvision_token'

export class ApiClientError extends Error {
  constructor(public code: string, message: string, public status: number, public traceId?: string) {
    super(message)
  }
}

// A tab is bound to the identity it loaded or explicitly logged into. It must never
// silently borrow a different account token written by another tab.
function readStoredToken(): string | null {
  const current = localStorage.getItem(TOKEN_KEY)
  if (current) return current
  const legacy = localStorage.getItem(LEGACY_TOKEN_KEY)
  if (legacy) { localStorage.setItem(TOKEN_KEY, legacy); localStorage.removeItem(LEGACY_TOKEN_KEY) }
  return legacy
}
let boundToken = readStoredToken()
let authGeneration = 0

function sessionChangedError() {
  return new ApiClientError('SESSION_CHANGED', '登录账户已在其他页面更改。为保护当前内容，请重新登录后继续。', 401)
}
function invalidateChangedSession() {
  boundToken = null
  authGeneration++
  // The listener clears private UI only; tokenStorage.clear is compare-and-clear
  // and cannot delete the new account's token from another tab.
  window.dispatchEvent(new Event('personal-platform:session-expired'))
}
export const tokenStorage = {
  get: () => boundToken,
  generation: () => authGeneration,
  set: (token: string) => {
    localStorage.setItem(TOKEN_KEY, token)
    localStorage.removeItem(LEGACY_TOKEN_KEY)
    boundToken = token
    authGeneration++
  },
  clear: () => {
    if (boundToken !== null && readStoredToken() === boundToken) {
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(LEGACY_TOKEN_KEY)
    }
    boundToken = null
    authGeneration++
  },
}
window.addEventListener('storage', event => {
  if (event.key !== null && event.key !== TOKEN_KEY && event.key !== LEGACY_TOKEN_KEY) return
  if (readStoredToken() !== boundToken) invalidateChangedSession()
})
interface RequestSession { token: string | null; generation: number; anonymous: boolean }
function captureSession(anonymous = false): RequestSession {
  if (!anonymous && readStoredToken() !== boundToken) {
    invalidateChangedSession()
    throw sessionChangedError()
  }
  return { token: anonymous ? null : boundToken, generation: authGeneration, anonymous }
}
function assertSession(session: RequestSession) {
  if (session.generation !== authGeneration) throw sessionChangedError()
  if (!session.anonymous && readStoredToken() !== session.token) {
    invalidateChangedSession()
    throw sessionChangedError()
  }
}
function expireSession(session: RequestSession) {
  // A delayed 401 for A must not clear a newer login for B, even in the same tab.
  if (!session.anonymous && session.token !== null && session.generation === authGeneration
      && boundToken === session.token && readStoredToken() === session.token) {
    tokenStorage.clear()
    window.dispatchEvent(new Event('personal-platform:session-expired'))
  }
}
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const session = captureSession(path === '/auth/login' || path === '/auth/register' || path.startsWith('/public/'))
  const headers = new Headers(init.headers)
  headers.delete('Authorization')
  if (session.token) headers.set('Authorization', `Bearer ${session.token}`)
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  const contentType = response.headers.get('content-type') || ''
  const envelope = contentType.includes('application/json')
    ? await response.json() as ApiEnvelope<T>
    : null
  assertSession(session)
  if (!response.ok || !envelope?.success) {
    if (response.status === 401) expireSession(session)
    throw new ApiClientError(
      envelope?.error?.code || 'REQUEST_FAILED',
      envelope?.error?.message || `请求失败 (${response.status})`,
      response.status,
      envelope?.traceId,
    )
  }
  return envelope.data
}

async function fetchBlob(path: string): Promise<Blob> {
  const session = captureSession()
  const headers = new Headers()
  if (session.token) headers.set('Authorization', `Bearer ${session.token}`)
  const response = await fetch(path.startsWith('/api/') ? path : `${API_BASE}${path}`, { headers })
  assertSession(session)
  if (!response.ok) {
    if (response.status === 401) expireSession(session)
    throw new ApiClientError('DOWNLOAD_FAILED', `下载失败 (${response.status})`, response.status)
  }
  const blob = await response.blob()
  assertSession(session)
  return blob
}

export const api = {
  login: (username: string, password: string) => request<LoginResponse>('/auth/login', {
    method: 'POST', body: JSON.stringify({ username, password }),
  }),
  switchAccount: (username: string, password: string) => request<LoginResponse>('/account/switch', { method: 'POST', body: JSON.stringify({ username, password }) }),
  register: (payload: { username: string; email: string; password: string }) =>
    request<UserView>('/auth/register', { method: 'POST', body: JSON.stringify(payload) }),
  me: () => request<UserView>('/auth/me'),
  dashboard: () => request<DashboardSummary>('/dashboard/summary'),
  files: () => request<FileView[]>('/files'),
  upload: (file: File, signal?: AbortSignal) => {
    const body = new FormData()
    body.append('file', file)
    return request<FileView>('/files', { method: 'POST', body, signal })
  },
  models: () => request<ModelView[]>('/public/models'),
  modelRuntime: () => request<ModelRuntimeView>('/models/runtime'),
  tasks: (signal?: AbortSignal) => request<InferenceView[]>('/inference/tasks', { signal }),
  recoverTask: (id: string, signal?: AbortSignal) => request<InferenceView>(`/inference/tasks/${encodeURIComponent(id)}/recover`, { method: 'POST', signal }),
  task: (id: string) => request<InferenceView>(`/inference/tasks/${id}`),
  run: (payload: { fileId: string; modelId: number; taskType: TaskType; enhancementEnabled: boolean }) =>
    request<InferenceView>('/inference/tasks', { method: 'POST', body: JSON.stringify(payload) }),
  users: () => request<UserView[]>('/users'),
  roles: () => request<RoleView[]>('/roles'),
  permissions: () => request<PermissionView[]>('/permissions'),
  createUser: (payload: { username: string; password: string; displayName: string; email: string; roleId: number }) =>
    request<UserView>('/users', { method: 'POST', body: JSON.stringify(payload) }),
  updateUser: (id: number, payload: Partial<{ displayName: string; email: string; password: string; status: UserStatus; roleId: number }>) =>
    request<UserView>(`/users/${id}`, { method: 'PATCH', body: JSON.stringify(payload) }),
  updateRolePermissions: (id: number, permissions: string[]) =>
    request<RoleView>(`/roles/${id}/permissions`, { method: 'PUT', body: JSON.stringify({ permissions }) }),
  createRole: (payload: { code: string; name: string; description: string; permissions: string[] }) =>
    request<RoleView>('/roles', { method: 'POST', body: JSON.stringify(payload) }),
  billing: () => request<BillingSummary>('/billing/summary'),
  providerBudgets: () => request<ProviderBudgetView[]>('/billing/providers'),
  createRecharge: (payload: { amount: number; method: PaymentMethod; bankLast4?: string; phone?: string }) =>
    request<RechargeOrderView>('/billing/recharges', { method: 'POST', body: JSON.stringify(payload) }),
  recharge: (id: string) => request<RechargeOrderView>(`/billing/recharges/${id}`),
  confirmRecharge: (id: string, verificationCode: string) =>
    request<RechargeOrderView>(`/billing/recharges/${id}/confirm`, { method: 'POST', body: JSON.stringify({ verificationCode }) }),
  publicPayment: (token: string) => request<PublicPaymentView>(`/public/payments/${encodeURIComponent(token)}`),
  completePublicPayment: (token: string) => request<PublicPaymentView>(`/public/payments/${encodeURIComponent(token)}/complete`, { method: 'POST' }),
  updateProviderBudget: (provider: string, monthlyBudgetCny: number) =>
    request<ProviderBudgetView>(`/billing/providers/${provider}/budget`, { method: 'PUT', body: JSON.stringify({ monthlyBudgetCny }) }),
  adminWallets: () => request<AdminWalletView[]>('/billing/admin/wallets'),
  adjustWallet: (userId: number, payload: { balanceDelta: number; monthlyQuotaCny?: number; reason: string }) =>
    request<AdminWalletView>(`/billing/admin/wallets/${userId}`, { method: 'PATCH', body: JSON.stringify(payload) }),
  credentials: () => request<ProviderCredentialView[]>('/billing/credentials'),
  createCredential: (payload: { provider: string; label: string; apiKey: string }) =>
    request<ProviderCredentialView>('/billing/credentials', { method: 'POST', body: JSON.stringify(payload) }),
  disableCredential: (id: number) => request<ProviderCredentialView>(`/billing/credentials/${id}`, { method: 'DELETE' }),
  workspaces: () => request<WorkspaceView[]>('/workspaces'),
  createWorkspace: (payload: { name: string; color: string }) => request<WorkspaceView>('/workspaces', { method: 'POST', body: JSON.stringify(payload) }),
  updateWorkspace: (id: number, payload: { name: string; color: string }) => request<WorkspaceView>(`/workspaces/${id}`, { method: 'PATCH', body: JSON.stringify(payload) }),
  upsertWorkspaceMember: (id: number, payload: { userId: number; role: WorkspaceMemberRole; permissions: string[] }) =>
    request<WorkspaceView>(`/workspaces/${id}/members`, { method: 'PUT', body: JSON.stringify(payload) }),
  removeWorkspaceMember: (id: number, userId: number) => request<WorkspaceView>(`/workspaces/${id}/members/${userId}`, { method: 'DELETE' }),
  workspaceDirectory: (id: number) => request<UserDirectoryView[]>(`/workspaces/${id}/directory`),
  leaveWorkspace: (id: number) => request<void>(`/workspaces/${id}/members/me`, { method: 'DELETE' }),
  groupMessages: (id: number, page = 0) => request<MessageView[]>(`/messages/groups/${id}?page=${page}`),
  sendGroupMessage: (id: number, payload: { body: string; files: File[]; replyToId?: string }) => {
    const body = new FormData()
    body.append('body', payload.body)
    if (payload.replyToId) body.append('replyToId', payload.replyToId)
    payload.files.forEach(file => body.append('files', file))
    return request<MessageView>(`/messages/groups/${id}`, { method: 'POST', body })
  },
  messageDirectory: () => request<MessageContactView[]>('/messages/directory'),
  inbox: () => request<MessageView[]>('/messages/inbox'),
  sent: () => request<MessageView[]>('/messages/sent'),
  message: (id: string) => request<MessageView>(`/messages/${id}`),
  sendMessage: (payload: { recipientIds: number[]; subject: string; body: string; files: File[]; replyToId?: string }) => {
    const body = new FormData()
    payload.recipientIds.forEach(id => body.append('recipientIds', String(id)))
    body.append('subject', payload.subject)
    body.append('body', payload.body)
    if (payload.replyToId) body.append('replyToId', payload.replyToId)
    payload.files.forEach(file => body.append('files', file))
    return request<MessageView>('/messages', { method: 'POST', body })
  },
  blobUrl: async (path: string) => URL.createObjectURL(await fetchBlob(path)),
  download: async (path: string, filename: string) => {
    const url = URL.createObjectURL(await fetchBlob(path))
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = filename
    document.body.appendChild(anchor)
    anchor.click()
    anchor.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  },

  // 知识库
  knowledgeTopics: () => request<KnowledgeTopicView[]>('/knowledge/topics'),
  knowledgeDomains: () => request<string[]>('/knowledge/domains'),
  createKnowledgeTopic: (payload: { domain: string; name: string; description?: string; sortOrder?: number }) =>
    request<KnowledgeTopicView>('/knowledge/topics', { method: 'POST', body: JSON.stringify(payload) }),
  updateKnowledgeTopic: (id: number, payload: Partial<{ domain: string; name: string; description: string; sortOrder: number }>) =>
    request<KnowledgeTopicView>(`/knowledge/topics/${id}`, { method: 'PATCH', body: JSON.stringify(payload) }),
  deleteKnowledgeTopic: (id: number) =>
    request<void>(`/knowledge/topics/${id}`, { method: 'DELETE' }),
  knowledgeEntries: (params: { topicId?: number; keyword?: string } = {}) => {
    const search = new URLSearchParams()
    if (params.topicId != null) search.set('topicId', String(params.topicId))
    if (params.keyword) search.set('keyword', params.keyword)
    const query = search.toString()
    return request<KnowledgeEntryView[]>(`/knowledge/entries${query ? `?${query}` : ''}`)
  },
  knowledgeEntry: (id: string) => request<KnowledgeEntryView>(`/knowledge/entries/${encodeURIComponent(id)}`),
  createKnowledgeEntry: (payload: { topicId: number; title: string; summary?: string; body: string; tags?: string; sortOrder?: number }) =>
    request<KnowledgeEntryView>('/knowledge/entries', { method: 'POST', body: JSON.stringify(payload) }),
  updateKnowledgeEntry: (id: string, payload: Partial<{ topicId: number; title: string; summary: string; body: string; tags: string; sortOrder: number }>) =>
    request<KnowledgeEntryView>(`/knowledge/entries/${encodeURIComponent(id)}`, { method: 'PATCH', body: JSON.stringify(payload) }),
  deleteKnowledgeEntry: (id: string) =>
    request<void>(`/knowledge/entries/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  // 笔记
  notes: (params: { status?: NoteStatusCode; keyword?: string } = {}) => {
    const search = new URLSearchParams()
    if (params.status) search.set('status', params.status)
    if (params.keyword) search.set('keyword', params.keyword)
    const query = search.toString()
    return request<NoteSummaryView[]>(`/notes${query ? `?${query}` : ''}`)
  },
  note: (id: string) => request<NoteView>(`/notes/${encodeURIComponent(id)}`),
  createNote: (payload: { clientId?: string; title: string; body: string; tags?: string; status?: NoteStatusCode; parentId?: string; library?: string; contentFormat?: "MARKDOWN" | "HTML" }) =>
    request<NoteView>('/notes', { method: 'POST', body: JSON.stringify(payload) }),
  updateNote: (id: string, payload: Partial<{ baseRevision: number; title: string; body: string; tags: string; status: NoteStatusCode; parentId: string; library: string; contentFormat: "MARKDOWN" | "HTML" }>) =>
    request<NoteView>(`/notes/${encodeURIComponent(id)}`, { method: 'PATCH', body: JSON.stringify(payload) }),
  deleteNote: (id: string) => request<void>(`/notes/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  addNoteReference: (id: string, payload: { referenceType: NoteReferenceType; referenceId: string; label?: string }) =>
    request<NoteView>(`/notes/${encodeURIComponent(id)}/references`, { method: 'POST', body: JSON.stringify(payload) }),
  removeNoteReference: (id: string, referenceId: number) =>
    request<NoteView>(`/notes/${encodeURIComponent(id)}/references/${referenceId}`, { method: 'DELETE' }),
  assistNote: (id: string, payload: { action: NoteAssistAction }) =>
    request<NoteAssistResponse>(`/notes/${encodeURIComponent(id)}/assist`, { method: 'POST', body: JSON.stringify(payload) }),
  assistDraft: (payload: { action: NoteAssistAction; body: string; title?: string }, signal?: AbortSignal) =>
    request<NoteAssistResponse>('/notes/assist', { method: 'POST', body: JSON.stringify(payload), signal }),
  exportNote: (id: string, format: NoteExportFormat, filename: string) =>
    api.download(`/notes/${encodeURIComponent(id)}/export?format=${format}`, filename),
  createNoteShare: (id: string, payload: { label?: string; expiresInDays?: number } = {}) =>
    request<NoteShareView>(`/notes/${encodeURIComponent(id)}/shares`, { method: 'POST', body: JSON.stringify(payload) }),
  noteShares: (id: string) => request<NoteShareView[]>(`/notes/${encodeURIComponent(id)}/shares`),
  revokeNoteShare: (id: string, shareId: string) =>
    request<void>(`/notes/${encodeURIComponent(id)}/shares/${shareId}`, { method: 'DELETE' }),
  sharedNote: (token: string) => request<SharedNoteView>(`/notes/shared/${encodeURIComponent(token)}`),
}
