import { request } from './client'
import type { PersonalAiPreview, PersonalAiResult } from '@/types/personal'
export interface Hit { id:string; kind:string; title:string; excerpt:string; url:string; updatedAt:string }
export interface Source extends Omit<Hit,'excerpt'> { body:string }
export interface SearchPage { items:Hit[];hasMore:boolean;page:number }
export interface SearchFilters { q:string;type:string;tag:string;since:string|null;group:number|null;sort:string }
export interface SavedSearch { id:string;name:string;filters:SearchFilters;createdAt:string }
export interface RecordItem { id:string; kind:string; title:string; data:any; revision:number; updatedAt:string }
const json=(method:string,data?:unknown)=>({method,body:data===undefined?undefined:JSON.stringify(data)})
export const researchApi={
 search:(params:Record<string,string>,signal?:AbortSignal)=>request<SearchPage>('/research/search?'+new URLSearchParams(params),{signal}),
 source:(kind:string,id:string,signal?:AbortSignal)=>request<Source>(`/research/sources/${encodeURIComponent(kind)}/${encodeURIComponent(id)}`,{signal}),
 bookmarks:(page=0,signal?:AbortSignal)=>request<SearchPage>(`/research/bookmarks?page=${page}`,{signal}),
 bookmarkKeys:(signal?:AbortSignal)=>request<{kind:string;id:string}[]>('/research/bookmarks/keys',{signal}),
 cleanupBookmarks:()=>request<number>('/research/bookmarks/cleanup',json('POST')),
 bookmark:(kind:string,id:string)=>request<void>('/research/bookmarks',json('PUT',{kind,id})),
 removeBookmark:(kind:string,id:string)=>request<void>(`/research/bookmarks/${encodeURIComponent(kind)}/${encodeURIComponent(id)}`,json('DELETE')),
 savedSearches:(signal?:AbortSignal)=>request<SavedSearch[]>('/research/saved-searches',{signal}),
 saveSearch:(name:string,filters:SearchFilters)=>request<SavedSearch>('/research/saved-searches',json('POST',{name,filters})),
 removeSearch:(id:string)=>request<void>(`/research/saved-searches/${encodeURIComponent(id)}`,json('DELETE')),
 preview:(provider:string,question:string,sources:Hit[],signal?:AbortSignal)=>request<PersonalAiPreview>('/research/answers/preview',{...json('POST',{provider,question,sources:sources.map(({id,kind})=>({id,kind}))}),signal}),
 answer:(previewToken:string,signal?:AbortSignal)=>request<PersonalAiResult>('/research/answers/execute',{...json('POST',{previewToken,confirmed:true}),signal}),
 records:(kind:string,page=0)=>request<RecordItem[]>(`/research/records?kind=${kind}&page=${page}`),
 save:(kind:string,title:string,data:unknown,existing?:RecordItem)=>request<RecordItem>(`/research/records/${kind}${existing?'/'+existing.id:''}`,json(existing?'PUT':'POST',{title,data,revision:existing?.revision||0})),
 delete:(row:RecordItem)=>request<void>(`/research/records/${row.id}?revision=${row.revision}`,json('DELETE')),
 grade:(row:RecordItem,grade:number)=>request<RecordItem>(`/research/records/${row.id}/grade`,json('POST',{grade,revision:row.revision})),
 capture:(kind:string,id:string)=>request<{id:string}>('/research/capture',json('POST',{kind,id})),
 trash:(page=0)=>request<{id:string;title:string;deletedAt:string}[]>(`/notes/trash?page=${page}`),
 restore:(id:string)=>request<void>(`/notes/${id}/restore`,json('POST')),
 purge:(id:string)=>request<void>(`/notes/${id}/purge`,json('DELETE')),
 versions:(id:string,page=0)=>request<{id:string;revision:number;title:string;createdAt:string}[]>(`/notes/${id}/versions?page=${page}`),
 version:(id:string,v:string)=>request<{id:string;title:string;body:string;revision:number;contentFormat:string}>(`/notes/${id}/versions/${v}`),
 restoreVersion:(id:string,v:string,baseRevision:number)=>request<void>(`/notes/${id}/versions/${v}/restore`,json('POST',{baseRevision})),
 notifications:()=>request<{id:string;title:string;url:string;count:number}[]>('/notifications'),
 evaluate:(payload:unknown)=>request<RecordItem>('/research/evaluations',json('POST',payload)),
 evaluations:()=>request<RecordItem[]>('/research/evaluations'),
}
