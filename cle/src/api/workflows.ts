import { request } from './client'
export interface WorkflowPreferences { groups:boolean; contacts:boolean; mail:boolean; study:boolean; weeklyTarget:number; studyDays:number[]; zoneId:string; revision:number }
export interface DueNote { id:string; title:string; date:string; repeatDays:number }
const json=(method:string,body:unknown)=>({method,body:JSON.stringify(body)})
export const workflowApi={
 preferences:()=>request<WorkflowPreferences>('/account/preferences'),
 savePreferences:(p:WorkflowPreferences)=>request<WorkflowPreferences>('/account/preferences',json('PUT',p)),
 reminder:(id:string)=>request<{date?:string;repeatDays?:number}>(`/notes/${id}/reminder`),
 remind:(id:string,date:string,repeatDays:number)=>request<void>(`/notes/${id}/reminder`,json('PUT',{date,repeatDays})),
 clearReminder:(id:string)=>request<void>(`/notes/${id}/reminder`,{method:'DELETE'}),
 completeReminder:(id:string)=>request<void>(`/notes/${id}/reminder/complete`,{method:'POST'}),
 due:()=>request<DueNote[]>('/notes/reminders'),
}
