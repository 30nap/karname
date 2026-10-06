import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, query } from '@/lib/api/client'
import type { Loan, LoanInput, LoanMethod, LoanPreview } from '@/lib/api/types'
import { invalidateFinance } from '@/features/transactions/invalidate'

export function useLoans() {
  return useQuery({ queryKey: ['loans'], queryFn: () => api.get<Loan[]>('/loans') })
}

export function useLoan(id: number) {
  return useQuery({ queryKey: ['loans', id], queryFn: () => api.get<Loan>(`/loans/${id}`), enabled: Number.isFinite(id) })
}

export function useLoanPreview(params: { principal: string; annualRate: string; termMonths: number; method: LoanMethod; installmentAmount?: string | null; firstDueDate: string } | null) {
  return useQuery({
    queryKey: ['loans', 'preview', params],
    queryFn: () => api.get<LoanPreview>(`/loans/preview${query({ ...params!, installmentAmount: params!.installmentAmount ?? undefined })}`),
    enabled: params !== null,
    placeholderData: keepPreviousData,
    retry: false,
    meta: { toastError: false },
  })
}

export function useSaveLoan() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...input }: LoanInput & { id?: number }) => (id ? api.put<Loan>(`/loans/${id}`, input) : api.post<Loan>('/loans', input)),
    meta: { toastError: false },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['loans'] })
      await invalidateFinance(queryClient)
    },
  })
}

export function useDeleteLoan() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, withAccount }: { id: number; withAccount: boolean }) => api.delete<void>(`/loans/${id}${query({ withAccount })}`),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['loans'] })
      await invalidateFinance(queryClient)
    },
  })
}

export function usePayInstallment() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, number, ...body }: { id: number; number: number; accountId?: number | null; date?: string; penalty?: string | null }) =>
      api.post<Loan>(`/loans/${id}/installments/${number}/payment`, body),
    meta: { toastError: false },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['loans'] })
      await invalidateFinance(queryClient)
    },
  })
}

export function useUndoPayment() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, number }: { id: number; number: number }) => api.delete<Loan>(`/loans/${id}/installments/${number}/payment`),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['loans'] })
      await invalidateFinance(queryClient)
    },
  })
}
