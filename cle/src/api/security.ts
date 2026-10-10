import { request } from './client'
const post = <T>(path: string, body: unknown) => request<T>(path, { method: 'POST', body: JSON.stringify(body) })
export interface SecurityState { mailAvailable: boolean; emailVerified: boolean; mfaEnabled: boolean; recoveryCodesLeft: number; delivery: string }
export const securityApi = {
 state: () => request<SecurityState>('/account/security'),
 email: (password: string) => post<void>('/account/security/email', { password }),
 setup: (password: string) => post<{secret: string; uri: string}>('/account/security/mfa/setup', { password }),
 enable: (password: string, code: string) => post<string[]>('/account/security/mfa/enable', { password, code }),
 disable: (password: string, code: string) => post<void>('/account/security/mfa/disable', { password, code }),
 forgot: (email: string) => post<void>('/auth/forgot-password', { email }),
 reset: (token: string, password: string) => post<void>('/auth/reset-password', { token, password }),
 verify: (token: string) => post<void>('/auth/verify-email', { token }),
}
