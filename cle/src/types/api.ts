export type UserStatus = 'ACTIVE' | 'DISABLED'
export type TaskType = 'LICENSE_PLATE' | 'RECEIPT' | 'VIDEO_ANALYSIS'
export type InferenceStatus = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
export type ModelStatus = 'ACTIVE' | 'INACTIVE'
export type ModelProvider = 'DEEPSEEK' | 'KIMI' | 'QWEN' | 'CUSTOM' | 'DEMO'
export type PaymentMethod = 'ALIPAY' | 'WECHAT' | 'BANK_CARD'
export type RechargeStatus = 'PENDING_PAYMENT' | 'PENDING_VERIFICATION' | 'VERIFICATION_LOCKED' | 'PAID' | 'EXPIRED' | 'CANCELLED'
export type WorkspaceMemberRole = 'OWNER' | 'ADMIN' | 'MEMBER' | 'VIEWER'

export interface ApiError { code: string; message: string }
export interface ApiEnvelope<T> {
  success: boolean
  data: T
  error: ApiError | null
  traceId: string
  timestamp: string
}

export interface UserView {
  id: number
  username: string
  displayName: string
  email: string
  status: UserStatus
  roleId: number
  roleCode: string
  roleName: string
  permissions: string[]
  createdAt: string
}

export interface LoginResponse {
  token: string
  tokenType: string
  expiresAt: string
  user: UserView
}

export interface RoleView {
  id: number
  code: string
  name: string
  description: string
  permissions: string[]
  createdAt: string
}

export interface PermissionView { code: string; label: string; group: string }

export interface FileView {
  id: string
  originalName: string
  contentType: string
  sizeBytes: number
  sha256: string
  source: 'UPLOAD' | 'ENHANCED'
  scanStatus: 'CLEAN' | 'INFECTED' | 'SKIPPED' | 'UNKNOWN'
  scanEngine: string | null
  storageBackend: string
  ownerName: string
  createdAt: string
  contentUrl: string
  downloadUrl: string
}

export interface ModelView {
  id: number
  code: string
  name: string
  version: string
  provider: ModelProvider
  taskType: TaskType
  status: ModelStatus
  description: string
  officialDocsUrl: string
  providerConsoleUrl: string
  createdAt: string
}

export interface ModelRuntimeView {
  mode: string
  provider: string
  model: string
  endpoint: string
  credentialConfigured: boolean
  capabilities: Record<string, boolean>
  limitations: string[]
  providers: Array<{
    provider: ModelProvider
    displayName: string
    model: string
    endpoint: string
    credentialConfigured: boolean
    configuredKeyCount: number
    videoSupported: boolean
  }>
}

export interface InferenceView {
  id: string
  traceId: string
  taskType: TaskType
  status: InferenceStatus
  enhancementEnabled: boolean
  inputFile: FileView
  outputFile: FileView | null
  model: ModelView
  requestedBy: string
  baselineConfidence: number | null
  optimizedConfidence: number | null
  baselineLatencyMs: number | null
  optimizedLatencyMs: number | null
  provider: ModelProvider
  inputTokens: number | null
  outputTokens: number | null
  costCny: number | null
  baselineResult: Record<string, unknown> | null
  optimizedResult: Record<string, unknown> | null
  errorMessage: string | null
  createdAt: string
  completedAt: string | null
  reportUrl: string
}

export interface DashboardSummary {
  totalTasks: number
  completedTasks: number
  failedTasks: number
  successRate: number
  averageConfidenceLift: number
  recentTasks: InferenceView[]
}

export interface WalletView {
  balanceCny: number
  monthlyQuotaCny: number
  monthSpentCny: number
  remainingQuotaCny: number
  quotaProgressPercent: number
  quotaPeriodStart: string
  updatedAt: string
}

export interface RechargeOrderView {
  id: string
  method: PaymentMethod
  amount: number
  status: RechargeStatus
  bankLast4: string | null
  phoneMasked: string | null
  verificationAttemptsRemaining: number
  qrPayload: string | null
  paymentToken: string | null
  createdAt: string
  expiresAt: string
  paidAt: string | null
  sandboxVerificationCode: string | null
  sandbox: boolean
}

export interface WalletLedgerView {
  id: number
  type: string
  amount: number
  balanceAfter: number
  referenceId: string | null
  description: string
  createdAt: string
}

export interface BillingSummary {
  wallet: WalletView
  recentRecharges: RechargeOrderView[]
  recentLedger: WalletLedgerView[]
}

export interface ProviderBudgetView {
  provider: ModelProvider
  displayName: string
  monthlyBudgetCny: number
  usedCny: number
  remainingCny: number
  progressPercent: number
  providerReportedBalance: number | null
  balanceSource: 'PROVIDER_API' | 'UNAVAILABLE' | 'OFFICIAL_COST_CENTER_REQUIRED' | 'LOCAL_BUDGET'
  configuredKeyCount: number
  rotationMode: string
  lastRotationAt: string | null
  updatedAt: string
}

export interface PublicPaymentView {
  orderId: string
  method: PaymentMethod
  amount: number
  status: RechargeStatus
  merchantName: string
  expiresAt: string
  paidAt: string | null
}

export interface AdminWalletView {
  userId: number
  username: string
  displayName: string
  roleCode: string
  wallet: WalletView
}

export interface ProviderCredentialView {
  id: number
  provider: ModelProvider
  label: string
  fingerprint: string
  active: boolean
  createdBy: string
  createdAt: string
  disabledAt: string | null
}

export interface WorkspaceMemberView {
  id: number
  userId: number
  username: string
  displayName: string
  role: WorkspaceMemberRole
  permissions: string[]
  createdAt: string
}

export interface WorkspaceView {
  id: number
  name: string
  slug: string
  color: string
  ownerId: number
  ownerName: string
  currentRole: WorkspaceMemberRole
  currentPermissions: string[]
  members: WorkspaceMemberView[]
  createdAt: string
  updatedAt: string
}

export interface UserDirectoryView { id: number; username: string; displayName: string; email: string }
export interface MessageAttachmentView { id: number; fileName: string; contentType: string; sizeBytes: number; downloadUrl: string }
export interface MessageView {
  id: string
  senderId: number
  senderName: string
  subject: string
  body: string
  recipients: UserDirectoryView[]
  attachments: MessageAttachmentView[]
  read: boolean
  createdAt: string
}

// ---------------------------------------------------------------------------
// 知识库与笔记
// ---------------------------------------------------------------------------

export type NoteStatusCode = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
export type NoteReferenceType = 'FILE' | 'TASK' | 'ENTRY'
export type NoteAssistAction = 'summarize' | 'outline' | 'tags' | 'tidy'
export type NoteExportFormat = 'md' | 'pdf' | 'docx'

export interface KnowledgeTopicView {
  id: number
  domain: string
  name: string
  description: string | null
  builtin: boolean
  sortOrder: number
  entryCount: number
  createdAt: string
  updatedAt: string
}

export interface KnowledgeEntryView {
  id: string
  topicId: number
  topicName: string
  domain: string
  title: string
  summary: string | null
  body: string
  tags: string[]
  builtin: boolean
  noteReferences: number
  sortOrder: number
  createdAt: string
  updatedAt: string
}

export interface NoteStatusView { code: NoteStatusCode; label: string }

export interface NoteReferenceView {
  id: number
  referenceType: NoteReferenceType
  referenceId: string
  label: string | null
  displayTitle: string
  displayMeta: string | null
  accessible: boolean
}

export interface NoteView {
  id: string
  title: string
  body: string
  tags: string[]
  status: NoteStatusView
  references: NoteReferenceView[]
  shareCount: number
  createdAt: string
  updatedAt: string
}

export interface NoteSummaryView {
  id: string
  title: string
  excerpt: string
  tags: string[]
  status: NoteStatusView
  shareCount: number
  createdAt: string
  updatedAt: string
}

/**
 * engine 如实标注结果来源：MODEL:<供应商> 表示调用了真实模型，
 * LOCAL_RULES 表示未配置密钥时由本地算法生成。前端必须原样展示，不得模糊。
 */
export interface NoteAssistResponse {
  action: NoteAssistAction
  engine: string
  result: string
  items: string[]
  note: string | null
  traceId: string
}

export interface NoteShareView {
  id: string
  token: string
  label: string | null
  shareUrl: string
  active: boolean
  viewCount: number
  expiresAt: string | null
  createdAt: string
}

export interface SharedNoteView {
  title: string
  body: string
  tags: string[]
  ownerName: string
  createdAt: string
  updatedAt: string
  references: NoteReferenceView[]
}
