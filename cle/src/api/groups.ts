import { request } from './client'
import type { MessageView, WorkspaceView } from '@/types/api'
export interface GroupFeatures {
  groupId: number; announcement: string; revision: number; pinned: boolean; muted: boolean; unread: number
  pinnedMessage: { id: string; body: string; senderName: string; createdAt: string } | null
}
export interface GroupPerson { id: number; identityCode: string; username: string; displayName: string }
export interface GroupDirectory { items: GroupPerson[]; hasMore: boolean; page: number }
export interface GroupResource { id:string; name:string; type:string; size:number; sender:string; createdAt:string }
export interface GroupSearchFilters { senderId?:number; after?:string; before?:string; attached?:boolean }
const json = (body: unknown) => ({ method: 'PUT', body: JSON.stringify(body) })
export const groupApi = {
  overview: () => request<{ groups: WorkspaceView[]; features: GroupFeatures[] }>('/workspaces/overview'),
  detail: (id: number) => request<WorkspaceView>(`/workspaces/${id}`),
  features: (id: number) => request<GroupFeatures>(`/workspaces/${id}/features`),
  preferences: (id: number, pinned: boolean, muted: boolean) => request<GroupFeatures>(`/workspaces/${id}/preferences`, json({ pinned, muted })),
  announcement: (id: number, text: string, revision: number) => request<GroupFeatures>(`/workspaces/${id}/announcement`, json({ text, revision })),
  pin: (id: number, messageId: string | null, revision: number) => request<GroupFeatures>(`/workspaces/${id}/pin`, json({ messageId, revision })),
  read: (id: number, messageId: string) => request<GroupFeatures>(`/workspaces/${id}/read`, { method: 'POST', body: JSON.stringify({ messageId }) }),
  people: (id: number, q: string, page = 0, signal?: AbortSignal) => request<GroupDirectory>(`/workspaces/${id}/people?${new URLSearchParams({ q, page: String(page) })}`, { signal }),
  search: (id: number, q: string, page = 0, signal?: AbortSignal, filters:GroupSearchFilters={}) => {const params=new URLSearchParams({q,page:String(page)});for(const [k,v] of Object.entries(filters))if(v!==undefined&&v!=='')params.set(k,String(v));return request<MessageView[]>(`/workspaces/${id}/search?${params}`,{signal})},
  resources:(id:number,q:string,page=0,signal?:AbortSignal)=>request<GroupResource[]>(`/workspaces/${id}/resources?${new URLSearchParams({q,page:String(page)})}`,{signal}),
}
