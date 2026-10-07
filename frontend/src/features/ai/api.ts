import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api/client'
import type {
  AiDraftInput,
  AiPresets,
  AiProvider,
  AiProviderInput,
  AiReportView,
  AiRoute,
  AiRouteInput,
  AiStatus,
  AiTestResult,
  AiUsageDay,
  CategorizeResult,
  ConversationSummary,
  ConversationView,
  DraftCommitResult,
  QuickAddResult,
  SmsResult,
} from '@/lib/api/types'
import { invalidateFinance } from '@/features/transactions/invalidate'

export function useAiStatus() {
  return useQuery({ queryKey: ['ai', 'status'], queryFn: () => api.get<AiStatus>('/ai/status'), staleTime: 60_000 })
}

/** Whether a task can be used right now; undefined while loading. */
export function useAiAvailable(task: 'CHAT' | 'EXTRACT' | 'REPORT') {
  const { data } = useAiStatus()
  return data ? data.enabled && data.tasks[task] : undefined
}

export function useQuickAdd() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (text: string) => api.post<QuickAddResult>('/ai/quick-add', { text }),
    meta: { toastError: false },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['ai', 'status'] }),
  })
}

export function useSmsImport() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (text: string) => api.post<SmsResult>('/ai/sms', { text }),
    meta: { toastError: false },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['ai', 'status'] }),
  })
}

export function useCommitDrafts() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (drafts: AiDraftInput[]) => api.post<DraftCommitResult>('/ai/drafts/commit', { drafts }),
    meta: { toastError: false },
    onSuccess: () => Promise.all([invalidateFinance(queryClient), queryClient.invalidateQueries({ queryKey: ['ai', 'conversation'] })]),
  })
}

export function useConversations() {
  return useQuery({ queryKey: ['ai', 'conversations'], queryFn: () => api.get<ConversationSummary[]>('/ai/conversations') })
}

export function conversationQuery(id: number | null) {
  return { queryKey: ['ai', 'conversation', id], queryFn: () => api.get<ConversationView>(`/ai/conversations/${id}`) }
}

/** A stored conversation; paused while this page streams a turn into it, so no half-written copy is read. */
export function useConversation(id: number | null, paused = false) {
  return useQuery({
    ...conversationQuery(id),
    enabled: id !== null && !paused,
    // a reply still being written (e.g. after the page was reloaded) appears when it is done
    refetchInterval: (query) => (query.state.data?.running ? 2000 : false),
  })
}

export function useRenameConversation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, title }: { id: number; title: string }) => api.patch<ConversationSummary>(`/ai/conversations/${id}`, { title }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['ai'] }),
  })
}

export function useDeleteConversation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete<void>(`/ai/conversations/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['ai', 'conversations'] }),
  })
}

export function stopConversation(id: number) {
  return api.post<void>(`/ai/conversations/${id}/stop`)
}

export function useMonthlyAiReport(month: string) {
  return useQuery({ queryKey: ['ai', 'report', month], queryFn: () => api.get<AiReportView>(`/ai/reports/${month}`) })
}

export function useGenerateAiReport() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (month: string) => api.post<AiReportView>(`/ai/reports/${month}`),
    meta: { toastError: false },
    onSuccess: (view) => {
      queryClient.setQueryData(['ai', 'report', view.month], view)
      return queryClient.invalidateQueries({ queryKey: ['ai', 'status'] })
    },
  })
}

export function useCategorize() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<CategorizeResult>('/ai/categorize'),
    meta: { toastError: false },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['ai', 'status'] }),
  })
}

export function useApplyCategories() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (items: { transactionId: number; categoryId: number }[]) => api.post<{ updated: number }>('/ai/categorize/apply', { items }),
    onSuccess: () => invalidateFinance(queryClient),
  })
}

// ---------------------------------------------------------------- administration

export function useAiPresets() {
  return useQuery({ queryKey: ['ai-admin', 'presets'], queryFn: () => api.get<AiPresets>('/admin/ai/presets'), staleTime: Infinity })
}

export function useAiProviders() {
  return useQuery({ queryKey: ['ai-admin', 'providers'], queryFn: () => api.get<AiProvider[]>('/admin/ai/providers') })
}

function invalidateAdmin(queryClient: ReturnType<typeof useQueryClient>) {
  return Promise.all([queryClient.invalidateQueries({ queryKey: ['ai-admin'] }), queryClient.invalidateQueries({ queryKey: ['ai'] })])
}

export function useSaveAiProvider() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, input }: { id?: number; input: AiProviderInput }) =>
      id ? api.put<AiProvider>(`/admin/ai/providers/${id}`, input) : api.post<AiProvider>('/admin/ai/providers', input),
    meta: { toastError: false },
    onSuccess: () => invalidateAdmin(queryClient),
  })
}

export function useDeleteAiProvider() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete<void>(`/admin/ai/providers/${id}`),
    onSuccess: () => invalidateAdmin(queryClient),
  })
}

export function useAiModels() {
  return useMutation({
    mutationFn: ({ id, input }: { id?: number; input: AiProviderInput }) =>
      api.post<string[]>(`/admin/ai/providers/models${id ? `?id=${id}` : ''}`, input),
    meta: { toastError: false },
  })
}

export function useTestAiProvider() {
  return useMutation({
    mutationFn: ({ id, input, model }: { id?: number; input: AiProviderInput; model?: string }) =>
      api.post<AiTestResult>(`/admin/ai/providers/test${id ? `?id=${id}` : ''}`, { provider: input, model }),
    meta: { toastError: false },
  })
}

export function useAiRoutes() {
  return useQuery({ queryKey: ['ai-admin', 'routes'], queryFn: () => api.get<AiRoute[]>('/admin/ai/routes') })
}

export function useSaveAiRoutes() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (routes: AiRouteInput[]) => api.put<AiRoute[]>('/admin/ai/routes', routes),
    meta: { toastError: false },
    onSuccess: () => invalidateAdmin(queryClient),
  })
}

export function useAiSettings() {
  return useQuery({ queryKey: ['ai-admin', 'settings'], queryFn: () => api.get<{ dailyLimit: number }>('/admin/ai/settings') })
}

export function useSaveAiSettings() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (dailyLimit: number) => api.put<{ dailyLimit: number }>('/admin/ai/settings', { dailyLimit }),
    onSuccess: () => invalidateAdmin(queryClient),
  })
}

export function useAiUsage(days = 30) {
  return useQuery({ queryKey: ['ai-admin', 'usage', days], queryFn: () => api.get<AiUsageDay[]>(`/admin/ai/usage?days=${days}`) })
}
