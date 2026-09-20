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

export const tokenStorage = {
  get: () => {
    const current = localStorage.getItem(TOKEN_KEY)
    if (current) return current
    const legacy = localStorage.getItem(LEGACY_TOKEN_KEY)
    if (legacy) { localStorage.setItem(TOKEN_KEY, legacy); localStorage.removeItem(LEGACY_TOKEN_KEY) }
    return legacy
  },
  set: (token: string) => { localStorage.setItem(TOKEN_KEY, token); localStorage.removeItem(LEGACY_TOKEN_KEY) },
  clear: () => { localStorage.removeItem(TOKEN_KEY); localStorage.removeItem(LEGACY_TOKEN_KEY) },
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  const token = tokenStorage.get()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  const response = await fetch(`${API_BASE}${path}`, { ...init, headers })
  const contentType = response.headers.get('content-type') || ''
  const envelope = contentType.includes('application/json')
    ? await response.json() as ApiEnvelope<T>
    : null
  if (!response.ok || !envelope?.success) {
    if (response.status === 401) tokenStorage.clear()
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
  const headers = new Headers()
  const token = tokenStorage.get()
  if (token) headers.set('Authorization', `Bearer ${token}`)
  const response = await fetch(path.startsWith('/api/') ? path : `${API_BASE}${path}`, { headers })
  if (!response.ok) throw new ApiClientError('DOWNLOAD_FAILED', `下载失败 (${response.status})`, response.status)
  return response.blob()
}

export const api = {
  login: (username: string, password: string) => request<LoginResponse>('/auth/login', {
    method: 'POST', body: JSON.stringify({ username, password }),
  }),
  register: (payload: { username: string; email: string; password: string }) =>
    request<UserView>('/auth/register', { method: 'POST', body: JSON.stringify(payload) }),
  me: () => request<UserView>('/auth/me'),
  dashboard: () => request<DashboardSummary>('/dashboard/summary'),
  files: () => request<FileView[]>('/files'),
  upload: (file: File) => {
    const body = new FormData()
    body.append('file', file)
    return request<FileView>('/files', { method: 'POST', body })
  },
  models: () => request<ModelView[]>('/public/models'),
  modelRuntime: () => request<ModelRuntimeView>('/models/runtime'),
  tasks: () => request<InferenceView[]>('/inference/tasks'),
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
  messageDirectory: () => request<UserDirectoryView[]>('/messages/directory'),
  inbox: () => request<MessageView[]>('/messages/inbox'),
  sent: () => request<MessageView[]>('/messages/sent'),
  message: (id: string) => request<MessageView>(`/messages/${id}`),
  sendMessage: (payload: { recipientIds: number[]; subject: string; body: string; files: File[] }) => {
    const body = new FormData()
    payload.recipientIds.forEach(id => body.append('recipientIds', String(id)))
    body.append('subject', payload.subject)
    body.append('body', payload.body)
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
  createNote: (payload: { title: string; body: string; tags?: string; status?: NoteStatusCode }) =>
    request<NoteView>('/notes', { method: 'POST', body: JSON.stringify(payload) }),
  updateNote: (id: string, payload: Partial<{ title: string; body: string; tags: string; status: NoteStatusCode }>) =>
    request<NoteView>(`/notes/${encodeURIComponent(id)}`, { method: 'PATCH', body: JSON.stringify(payload) }),
  deleteNote: (id: string) => request<void>(`/notes/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  addNoteReference: (id: string, payload: { referenceType: NoteReferenceType; referenceId: string; label?: string }) =>
    request<NoteView>(`/notes/${encodeURIComponent(id)}/references`, { method: 'POST', body: JSON.stringify(payload) }),
  removeNoteReference: (id: string, referenceId: number) =>
    request<NoteView>(`/notes/${encodeURIComponent(id)}/references/${referenceId}`, { method: 'DELETE' }),
  assistNote: (id: string, payload: { action: NoteAssistAction }) =>
    request<NoteAssistResponse>(`/notes/${encodeURIComponent(id)}/assist`, { method: 'POST', body: JSON.stringify(payload) }),
  assistDraft: (payload: { action: NoteAssistAction; body: string; title?: string }) =>
    request<NoteAssistResponse>('/notes/assist', { method: 'POST', body: JSON.stringify(payload) }),
  exportNote: (id: string, format: NoteExportFormat, filename: string) =>
    api.download(`/notes/${encodeURIComponent(id)}/export?format=${format}`, filename),
  createNoteShare: (id: string, payload: { label?: string; expiresInDays?: number } = {}) =>
    request<NoteShareView>(`/notes/${encodeURIComponent(id)}/shares`, { method: 'POST', body: JSON.stringify(payload) }),
  noteShares: (id: string) => request<NoteShareView[]>(`/notes/${encodeURIComponent(id)}/shares`),
  revokeNoteShare: (id: string, shareId: string) =>
    request<void>(`/notes/${encodeURIComponent(id)}/shares/${shareId}`, { method: 'DELETE' }),
  sharedNote: (token: string) => request<SharedNoteView>(`/notes/shared/${encodeURIComponent(token)}`),
}
