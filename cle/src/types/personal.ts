import type { NoteAssistAction, NoteAssistResponse } from './api'

export interface ExperimentSource {
  taskId: string
  title: string
  taskType: string
  modelName: string
  status: string
  createdAt: string
  traceId: string
}
export interface ExperimentPreview { markdown: string; sourceIds: string[] }
export type PersonalAiAction = NoteAssistAction | 'draft' | 'code-annotate'
export interface PersonalAiProvider {
  provider: string
  displayName: string
  remoteEnabled: boolean
  protocol: string
  baseUrls: string[]
  customEndpointAllowed: boolean
}
export interface PersonalAiSetting {
  provider: string
  model: string
  baseUrl: string
  enabled: boolean
  configured: boolean
  revision: number
  updatedAt: string
}
export interface PersonalAiPreview {
  previewToken: string
  expiresAt: string
  provider: string
  model: string
  endpoint: string
  action: PersonalAiAction
  context: string
  systemPrompt: string
  outboundBytes: number
}
export interface PersonalAiResult extends Omit<NoteAssistResponse, 'action'> {
  action: PersonalAiAction
  // This confirms usage metadata only; generated content still requires manual application.
  persistenceStatus: 'SAVED' | 'UNCONFIRMED'
  warning: string | null
  inputTokens?: number | null
  outputTokens?: number | null
}
export interface PersonalAiUsage {
  id: number | string
  provider: string
  model: string
  action: PersonalAiAction
  status: 'SUCCEEDED' | 'FAILED'
  inputTokens: number | null
  outputTokens: number | null
  errorCode: string | null
  createdAt: string
}
export interface AccountSession {
  id: string
  createdAt: string
  expiresAt: string
  current: boolean
  userAgent?: string
  lastSeenAt?: string
}

export type PersonalRecognitionTask = 'RECEIPT' | 'LICENSE_PLATE' | 'IMAGE_UNDERSTANDING'
export interface RecognitionPreview {
  previewToken: string
  expiresAt: string
  provider: string
  model: string
  endpoint: string
  fileId: string
  fileName: string
  sha256: string
  mime: string
  sizeBytes: number
  taskType: PersonalRecognitionTask
  systemPrompt: string
  prompt: string
  outboundBytes: number
}
export interface RecognitionResult {
  id: string | null
  persistenceStatus: 'SAVED' | 'UNCONFIRMED'
  warning: string | null
  provider: string
  model: string
  taskType: PersonalRecognitionTask
  fileId: string
  fileName: string
  result: string
  inputTokens: number | null
  outputTokens: number | null
  createdAt: string
}

export interface AccountUsage {
  ai: { total: number; succeeded: number; failed: number; knownInputTokens: number; knownOutputTokens: number; unknownUsageCalls: number }
  experiments: { total: number; completed: number; failed: number }
  files: { count: number; bytes: number }
  noteCount: number
  recognitionCount: number
}

export interface PersonalAiMemory { id: string; title: string; body: string; enabled: boolean; revision: number; updatedAt: string }
export interface TrainingSample { text: string; label: string }
export interface TrainingEnvironment { ready: boolean; framework: string; version: string; device: string; template: string; maxSamples: number; maxEpochs: number }
export interface TrainingJob { id: string; name: string; status: string; message: string; createdAt: number; epochs: number; epoch: number; samples: number; labels: string[]; trainSamples?: number; validationSamples?: number; metrics: { epoch: number; loss: number; accuracy: number }[] }
