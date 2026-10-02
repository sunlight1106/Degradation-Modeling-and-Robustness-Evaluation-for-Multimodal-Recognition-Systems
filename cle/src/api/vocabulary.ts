import { request } from './client'
export type VocabularyMode = 'LEARN' | 'REVIEW' | 'MISTAKES'
export interface VocabularySettings { zoneId: string | null; dailyGoal: number; selectedBookId: string | null; masteryTarget: number }
export interface VocabularyBook { id: string; title: string; description: string; attribution: string; level: string; owned: boolean; totalWords: number; learned: number; learning: number; due: number; mistakes: number }
export interface VocabularyDay { date: string | null; answers: number; correct: number; learned: number; reviews: number }
export interface VocabularyDashboard { settings: VocabularySettings; books: VocabularyBook[]; today: VocabularyDay; streak: number; history: VocabularyDay[]; dueTotal: number; starredTotal: number }
export interface VocabularyQuestion { id: string; term: string; ipa: string; pos: string; mode: VocabularyMode; learningCorrect: number; masteryTarget: number; options: { id: string; meaning: string }[]; expiresAt: string }
export interface VocabularyNext { question: VocabularyQuestion | null; reason: string | null; remaining: number }
export interface VocabularyAnswer { questionId: string; wordId: string; correct: boolean; correctOptionId: string; meaning: string; example: string; exampleTranslation: string; learningCorrect: number; masteryTarget: number; newlyLearned: boolean; dueDate: string | null; reviewStage: number; starred: boolean; message: string }
export interface VocabularyWord { id: string; term: string; ipa: string; pos: string; meaning: string; example: string; exampleTranslation: string; learningCorrect: number; wrongCount: number; mistake: boolean; starred: boolean; dueDate: string | null; reviewStage: number }
export interface VocabularyWordPage { items: VocabularyWord[]; total: number; page: number; pageSize: number }
export interface VocabularyImport { schemaVersion?: 1; title: string; description: string; attribution: string; rightsConfirmed: boolean; words: { term: string; ipa: string; pos: string; meaning: string; example: string; exampleTranslation: string; distractors: string[] }[] }
export const vocabularyApi = {
  dashboard: () => request<VocabularyDashboard>('/vocabulary/dashboard'),
  settings: (input: Omit<VocabularySettings, 'masteryTarget'>) => request<VocabularySettings>('/vocabulary/settings', { method: 'PUT', body: JSON.stringify(input) }),
  next: (bookId: string, mode: VocabularyMode) => request<VocabularyNext>('/vocabulary/next', { method: 'POST', body: JSON.stringify({ bookId, mode }) }),
  answer: (id: string, optionId: string) => request<VocabularyAnswer>(`/vocabulary/questions/${encodeURIComponent(id)}/answer`, { method: 'POST', body: JSON.stringify({ optionId }) }),
  words: (id: string, filter: string, query: string, page: number) => request<VocabularyWordPage>(`/vocabulary/books/${encodeURIComponent(id)}/words?${new URLSearchParams({ filter, query, page: String(page) })}`),
  star: (id: string, starred: boolean) => request<VocabularyWord>(`/vocabulary/words/${encodeURIComponent(id)}/star`, { method: 'PUT', body: JSON.stringify({ starred }) }),
  import: (input: VocabularyImport) => request<VocabularyBook>('/vocabulary/books/import', { method: 'POST', body: JSON.stringify(input) }),
}
