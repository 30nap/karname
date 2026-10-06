import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api/client'
import type { Cheque, ChequeInput, ChequeStatus } from '@/lib/api/types'
import { invalidateFinance } from '@/features/transactions/invalidate'

export function useCheques() {
  return useQuery({ queryKey: ['cheques'], queryFn: () => api.get<Cheque[]>('/cheques') })
}

export function useSaveCheque() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...input }: Partial<ChequeInput> & { id?: number }) => (id ? api.put<Cheque>(`/cheques/${id}`, input) : api.post<Cheque>('/cheques', input)),
    meta: { toastError: false },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['cheques'] })
      await invalidateFinance(queryClient)
    },
  })
}

export function useDeleteCheque() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete<void>(`/cheques/${id}`),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['cheques'] })
      await invalidateFinance(queryClient)
    },
  })
}

export function useChequeStatus() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: { id: number; status: ChequeStatus; date?: string; accountId?: number | null }) => api.post<Cheque>(`/cheques/${id}/status`, body),
    meta: { toastError: false },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['cheques'] })
      await invalidateFinance(queryClient)
    },
  })
}
