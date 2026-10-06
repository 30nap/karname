import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api/client'
import type { Occurrence, RecurringInput, RecurringRule } from '@/lib/api/types'
import { invalidateFinance } from '@/features/transactions/invalidate'

/** Rules feed the cash-flow forecast and the reminders, so those refresh with them. */
const invalidatePlans = (queryClient: QueryClient) =>
  Promise.all(['recurring', 'forecast', 'notifications'].map((key) => queryClient.invalidateQueries({ queryKey: [key] })))

export function useRecurringRules() {
  return useQuery({ queryKey: ['recurring'], queryFn: () => api.get<RecurringRule[]>('/recurring') })
}

export function usePendingOccurrences() {
  return useQuery({ queryKey: ['recurring', 'pending'], queryFn: () => api.get<Occurrence[]>('/recurring/pending') })
}

/** The editable fields of a rule (updates replace the whole rule). */
export function toRuleInput(rule: RecurringRule): RecurringInput {
  return {
    name: rule.name, type: rule.type, accountId: rule.accountId, toAccountId: rule.toAccountId, amount: rule.amount, toAmount: rule.toAmount,
    categoryId: rule.categoryId, description: rule.description, frequency: rule.frequency, interval: rule.interval, dayOfMonth: rule.dayOfMonth,
    dayOfWeek: rule.dayOfWeek, monthOfYear: rule.monthOfYear, startDate: rule.startDate, endDate: rule.endDate, mode: rule.mode, active: rule.active,
  }
}

export function useSaveRule() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...input }: RecurringInput & { id?: number }) =>
      id ? api.put<RecurringRule>(`/recurring/${id}`, input) : api.post<RecurringRule>('/recurring', input),
    meta: { toastError: false },
    onSuccess: () => invalidatePlans(queryClient),
  })
}

export function useDeleteRule() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete<void>(`/recurring/${id}`),
    onSuccess: () => invalidatePlans(queryClient),
  })
}

export function usePostOccurrence() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ ruleId, date, ...body }: { ruleId: number; date: string; amount?: string; actualDate?: string }) =>
      api.post<{ transactionId: number }>(`/recurring/${ruleId}/occurrences/${date}/post`, { amount: body.amount, date: body.actualDate }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['recurring'] })
      await invalidateFinance(queryClient)
    },
  })
}

export function useSkipOccurrence() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ ruleId, date, undo }: { ruleId: number; date: string; undo?: boolean }) =>
      undo ? api.delete<void>(`/recurring/${ruleId}/occurrences/${date}/skip`) : api.post<void>(`/recurring/${ruleId}/occurrences/${date}/skip`),
    onSuccess: () => invalidatePlans(queryClient),
  })
}
