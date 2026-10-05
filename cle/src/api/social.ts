import { request } from './client'

export interface Person { id: number; identityCode: string; username: string; displayName: string }
export interface Contact extends Omit<Person, 'id'> { id: number; userId: number; status: 'PENDING' | 'ACCEPTED' | 'REMOVED' | 'REJECTED'; incoming: boolean; blockedByMe: boolean; available: boolean; remark: string; pinned: boolean; muted: boolean; unreadCount: number; clearedThrough: number }
export interface ChatMessage { id: number; senderId: number; senderName: string; clientId: string; body: string; createdAt: string }
export type ContactAction = 'accept' | 'reject' | 'withdraw' | 'remove' | 'block' | 'unblock'
const json = (method: string, body: unknown) => ({ method, body: JSON.stringify(body) })
export const socialApi = {
  search: (q: string) => request<Person[]>(`/social/people?q=${encodeURIComponent(q)}`),
  contacts: () => request<Contact[]>('/social/contacts'),
  settings: () => request<{ discoverable: boolean }>('/social/settings'),
  discovery: (discoverable: boolean) => request<{ discoverable: boolean }>('/social/settings', json('PUT', { discoverable })),
  add: (userId: number) => request<void>('/social/contacts', json('POST', { userId })),
  act: (id: number, action: ContactAction) => request<void>(`/social/contacts/${id}`, json('PATCH', { action })),
  preferences: (id: number, settings: Partial<Pick<Contact, 'remark' | 'pinned' | 'muted'>>) => request<void>(`/social/contacts/${id}/preferences`, json('PATCH', settings)),
  read: (id: number, through: number) => request<void>(`/social/contacts/${id}/read`, json('POST', { through })),
  clearHistory: (id: number) => request<void>(`/social/contacts/${id}/clear-history`, { method: 'POST' }),
  messages: (id: number, cursor: { after?: number; before?: number } = {}) => request<ChatMessage[]>(`/social/contacts/${id}/messages?${new URLSearchParams(Object.entries(cursor).map(([key, value]) => [key, String(value)]))}`),
  send: (id: number, clientId: string, body: string) => request<ChatMessage>(`/social/contacts/${id}/messages`, json('POST', { clientId, body })),
}
