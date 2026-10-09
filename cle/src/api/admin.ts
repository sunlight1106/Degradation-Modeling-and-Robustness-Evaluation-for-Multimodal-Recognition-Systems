import { request } from './client'
import type { UserView } from '@/types/api'
export interface Page<T> { items: T[]; total: number; page: number; size: number }
export interface Access { rolePermissions: string[]; grants: string[]; denies: string[]; effectivePermissions: string[]; expiresAt: string | null }
export interface UserDetail { user: UserView; access: Access; notes: number; files: number; storageBytes: number; experiments: number; aiCalls: number; activeSessions: number; lastLoginAt: string | null }
export interface Day { date: string; registrations: number; loginUsers: number; notes: number; aiCalls: number; experiments: number }
export interface Statistics { users: number; enabledUsers: number; expiredUsers: number; disabledUsers: number; recentUsers: number; roles: number; notes: number; files: number; storageBytes: number; aiCalls: number; experiments: number; zone: string; generatedAt: string; days: Day[] }
export interface Audit { id: number; operatorId: number; operatorName: string; action: string; targetType: string; targetId: number; detail: string; createdAt: string }
export const adminApi = {
  users: (q: string, status: string, roleId: string, page: number) => {
    const params = new URLSearchParams({ q, page: String(page), size: '25' })
    if (status) params.set('status', status)
    if (roleId) params.set('roleId', roleId)
    return request<Page<UserView>>(`/admin/users?${params}`)
  },
  detail: (id: number) => request<UserDetail>(`/admin/users/${id}`),
  access: (id: number, payload: { grants: string[]; denies: string[]; expiresAt: string | null }) => request<Access>(`/admin/users/${id}/access`, { method: 'PUT', body: JSON.stringify(payload) }),
  revoke: (id: number) => request<void>(`/admin/users/${id}/revoke-sessions`, { method: 'POST' }),
  statistics: (days: number) => request<Statistics>(`/admin/statistics?days=${days}`),
  audit: (page: number) => request<Page<Audit>>(`/admin/audit?page=${page}&size=25`),
}
