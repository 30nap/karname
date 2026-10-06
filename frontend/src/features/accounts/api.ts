import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api/client'
import type { Account, AccountInput, CostBasis, Transaction } from '@/lib/api/types'
import { invalidateFinance } from '@/features/transactions/invalidate'

export function useAccounts(includeArchived = false) {
  return useQuery({
    queryKey: ['accounts', { includeArchived }],
    queryFn: () => api.get<Account[]>(`/accounts?includeArchived=${includeArchived}`),
  })
}

export function useAccount(id: number) {
  return useQuery({ queryKey: ['accounts', id], queryFn: () => api.get<Account>(`/accounts/${id}`) })
}

export function useCostBasis(account: Account | undefined) {
  const applicable = !!account && account.commodity !== 'IRT' && !account.liability
  return useQuery({
    queryKey: ['cost-basis', account?.id],
    queryFn: () => api.get<CostBasis>(`/accounts/${account!.id}/cost-basis`),
    enabled: applicable,
  })
}

export function useSaveAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...input }: AccountInput & { id?: number }) =>
      id ? api.put<Account>(`/accounts/${id}`, input) : api.post<Account>('/accounts', input),
    meta: { toastError: false },
    onSuccess: () => invalidateFinance(queryClient),
  })
}

export function useArchiveAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, archived }: { id: number; archived: boolean }) => api.post<Account>(`/accounts/${id}/archive`, { archived }),
    onSuccess: () => invalidateFinance(queryClient),
  })
}

export function useDeleteAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, force }: { id: number; force: boolean }) => api.delete<void>(`/accounts/${id}?force=${force}`),
    meta: { toastError: false },
    onSuccess: () => invalidateFinance(queryClient),
  })
}

export function useReconcileAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, actualBalance, date }: { id: number; actualBalance: string; date: string }) =>
      api.post<Transaction>(`/accounts/${id}/reconcile`, { actualBalance, date }),
    meta: { toastError: false },
    onSuccess: () => invalidateFinance(queryClient),
  })
}
