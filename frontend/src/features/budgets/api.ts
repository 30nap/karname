import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, query } from '@/lib/api/client'
import type { BudgetMonth, BudgetSuggestion } from '@/lib/api/types'

export function useBudgetMonth(month: string) {
  return useQuery({ queryKey: ['budgets', month], queryFn: () => api.get<BudgetMonth>(`/budgets${query({ month })}`) })
}

export function useBudgetSuggestions(month: string, enabled = true) {
  return useQuery({
    queryKey: ['budgets', 'suggestions', month],
    queryFn: () => api.get<BudgetSuggestion[]>(`/budgets/suggestions${query({ month })}`),
    enabled,
  })
}

export function useSetBudget() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ month, categoryId, amount, recurring }: { month: string; categoryId: number; amount: string; recurring: boolean }) =>
      api.put<BudgetMonth>(`/budgets/${month}/${categoryId}`, { amount, recurring }),
    meta: { toastError: false },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['budgets'] }),
  })
}

export function useRemoveBudget() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ month, categoryId, scope }: { month: string; categoryId: number; scope: 'MONTH' | 'FORWARD' }) =>
      api.delete<BudgetMonth>(`/budgets/${month}/${categoryId}${query({ scope })}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['budgets'] }),
  })
}
