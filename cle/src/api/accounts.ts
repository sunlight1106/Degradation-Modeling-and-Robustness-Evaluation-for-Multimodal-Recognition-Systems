import { request } from './client'
const post=<T>(path:string,body:unknown)=>request<T>(path,{method:'POST',body:JSON.stringify(body)})
export type ClosureMode='SUSPEND'|'PURGE'
export interface ClosureTicket { token:string; mode:ClosureMode; waitSeconds:number; expiresAt:string; notes:number }
export interface AccountActivity { action:string; outcome:string; network:string; device:string; createdAt:string }
export const accountApi={
 activity:(page=0)=>request<AccountActivity[]>(`/account/activity?page=${page}`),
 bind:(email:string,password:string,otp:string)=>post<void>('/account/bindings/email',{email,password,otp}),
 prepare:(mode:ClosureMode,currentPassword:string)=>post<ClosureTicket>('/account/closure/prepare',{mode,currentPassword}),
 close:(token:string,confirmation:string,currentPassword:string,otp:string)=>post<void>('/account/closure/confirm',{token,confirmation,currentPassword,otp}),
}
