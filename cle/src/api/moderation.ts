import {request} from './client'

export interface ReportRow {id:string;source_type:string;target_name:string;target_identity:string;reason:string;status:string;urgent:boolean;created_at:string;review_reason?:string;reviewed_at?:string;evidence?:string}
export interface Penalty {id:string;report_id:string;kind:string;features:string;reason:string;automatic:boolean;created_at:string;expires_at:string;revoked_at?:string;appeal?:string;appeal_at?:string;appeal_reply?:string}
export interface ModerationEvent {id:string;action:string;detail:string;created_at:string;actor_name?:string}
export interface CaseDetail {report:ReportRow;penalties:Penalty[];events:ModerationEvent[]}
export interface OwnSafety {reports:ReportRow[];penalties:Penalty[];decisions?:{id:string;review_reason:string;reviewed_at:string}[]}
export const labels:Record<string,string>={PENDING:'待审核',RESOLVED:'已处理',DISMISSED:'已驳回',MUTE:'禁言',FEATURE:'功能限制',BAN:'封禁',WARNING:'警告',DISMISS:'驳回举报',MESSAGE:'站内信 / 群聊',CHAT:'私聊'}
export const activePenalty=(p:Penalty)=>!p.revoked_at&&Date.parse(p.expires_at)>Date.now()
export const dateLabel=(s?:string)=>s?new Date(s).toLocaleString('zh-CN'):'—'
export const safetyApi={
 mine:()=>request<OwnSafety>('/moderation/mine'),
 queue:(status:string,page:number,signal?:AbortSignal)=>request<{items:ReportRow[];total:number;page:number;features:Record<string,string>}>(`/moderation/reports?${new URLSearchParams({status,page:String(page)})}`,{signal}),
 detail:(id:string,signal?:AbortSignal)=>request<CaseDetail>(`/moderation/reports/${id}`,{signal}),
 post:(path:string,body:unknown)=>request(`/moderation/${path}`,{method:'POST',body:JSON.stringify(body)}),
}
