import { request } from './client'
import type { UserView } from '@/types/api'
import type { AccountUsage, AccountSession, ExperimentPreview, ExperimentSource, PersonalAiAction, PersonalAiPreview, PersonalAiProvider, PersonalAiResult, PersonalAiSetting, PersonalAiUsage, PersonalRecognitionTask, RecognitionPreview, RecognitionResult } from '@/types/personal'

const json = (method: string, data?: unknown, signal?: AbortSignal): RequestInit => ({ method, body: data === undefined ? undefined : JSON.stringify(data), signal })
export const personalApi = {
  providers: (signal?: AbortSignal) => request<PersonalAiProvider[]>('/personal-ai/providers', { signal }),
  settings: (signal?: AbortSignal) => request<PersonalAiSetting[]>('/personal-ai/settings', { signal }),
  saveSetting: (provider: string, payload: { model: string; baseUrl?: string; apiKey?: string; enabled: boolean }) => request<PersonalAiSetting>(`/personal-ai/settings/${encodeURIComponent(provider)}`, json('PUT', payload)),
  deleteSetting: (provider: string) => request<void>(`/personal-ai/settings/${encodeURIComponent(provider)}`, json('DELETE')),
  preview: (payload: { provider: string; action: PersonalAiAction; title: string; body: string; selectedTaskIds?: string[] }, signal?: AbortSignal) => request<PersonalAiPreview>('/personal-ai/preview', json('POST', payload, signal)),
  execute: (previewToken: string, signal?: AbortSignal) => request<PersonalAiResult>('/personal-ai/execute', json('POST', { previewToken, confirmed: true }, signal)),
  usage: () => request<PersonalAiUsage[]>('/personal-ai/usage'),
  experiments: (signal?: AbortSignal) => request<ExperimentSource[]>('/notes/sources/experiments', { signal }),
  experimentPreview: (taskIds: string[], signal?: AbortSignal) => request<ExperimentPreview>('/notes/sources/experiments/preview', json('POST', { taskIds }, signal)),
  logout: () => request<void>('/account/logout', json('POST')),
  recognitionPreview: (payload: { provider: string; fileId: string; taskType: PersonalRecognitionTask }, signal?: AbortSignal) => request<RecognitionPreview>('/personal-ai/recognition/preview', json('POST', payload, signal)),
  recognize: (previewToken: string, signal?: AbortSignal) => request<RecognitionResult>('/personal-ai/recognition/execute', json('POST', { previewToken, confirmed: true }, signal)),
  recognitionResults: (signal?: AbortSignal) => request<RecognitionResult[]>('/personal-ai/recognition/results', { signal }),
  accountUsage: () => request<AccountUsage>('/account/usage'),
  profile: () => request<UserView>('/account/profile'),
  updateProfile: (payload: { displayName: string; email: string; currentPassword?: string }) => request<UserView>('/account/profile', json('PATCH', payload)),
  changePassword: (currentPassword: string, newPassword: string) => request<void>('/account/password', json('POST', { currentPassword, newPassword })),
  sessions: () => request<AccountSession[]>('/account/sessions'),
  revokeSession: (id: string, currentPassword: string) => request<void>(`/account/sessions/${encodeURIComponent(id)}`, json('DELETE', { currentPassword })),
  revokeOtherSessions: (currentPassword: string) => request<void>('/account/sessions/revoke-others', json('POST', { currentPassword })),
  exportData: (currentPassword: string) => request<unknown>('/account/export', json('POST', { currentPassword })),
}
