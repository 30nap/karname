import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, query } from '@/lib/api/client'
import type { Dashboard, NetWorth, NetWorthPoint, Transaction, TransactionFilter, TransactionInput, TransactionPage } from '@/lib/api/types'
import { invalidateFinance } from './invalidate'

const PAGE_SIZE = 40

function filterQuery(filter: TransactionFilter, page: number, size: number) {
  return query({ ...filter, type: filter.type, uncategorized: filter.uncategorized || undefined, page, size })
}

export function useTransactionPages(filter: TransactionFilter) {
  return useInfiniteQuery({
    queryKey: ['transactions', 'list', filter],
    queryFn: ({ pageParam }) => api.get<TransactionPage>(`/transactions${filterQuery(filter, pageParam, PAGE_SIZE)}`),
    initialPageParam: 0,
    getNextPageParam: (last) => ((last.page + 1) * last.size < last.total ? last.page + 1 : undefined),
    placeholderData: keepPreviousData,
  })
}

export function useRecentTransactions(filter: TransactionFilter, size = 10) {
  return useQuery({
    queryKey: ['transactions', 'recent', filter, size],
    queryFn: () => api.get<TransactionPage>(`/transactions${filterQuery(filter, 0, size)}`),
  })
}

export function useSaveTransaction() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...input }: TransactionInput & { id?: number }) =>
      id ? api.put<Transaction>(`/transactions/${id}`, input) : api.post<Transaction>('/transactions', input),
    meta: { toastError: false },
    onSuccess: () => invalidateFinance(queryClient),
  })
}

export function useDeleteTransaction() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete<void>(`/transactions/${id}`),
    onSuccess: () => invalidateFinance(queryClient),
  })
}

export function useDashboard() {
  return useQuery({ queryKey: ['dashboard'], queryFn: () => api.get<Dashboard>('/dashboard') })
}

export function useNetWorth() {
  return useQuery({ queryKey: ['net-worth'], queryFn: () => api.get<NetWorth>('/net-worth') })
}

export function useNetWorthHistory(months = 12) {
  return useQuery({ queryKey: ['net-worth', 'history', months], queryFn: () => api.get<NetWorthPoint[]>(`/net-worth/history?months=${months}`) })
}
