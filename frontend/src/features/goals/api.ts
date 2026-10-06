import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, query } from '@/lib/api/client'
import type { Goal, GoalInput } from '@/lib/api/types'

export function useGoals(includeArchived = false) {
  return useQuery({ queryKey: ['goals', { includeArchived }], queryFn: () => api.get<Goal[]>(`/goals${query({ includeArchived })}`) })
}

export function useSaveGoal() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...input }: GoalInput & { id?: number }) => (id ? api.put<Goal>(`/goals/${id}`, input) : api.post<Goal>('/goals', input)),
    meta: { toastError: false },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['goals'] }),
  })
}

export function useDeleteGoal() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete<void>(`/goals/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['goals'] }),
  })
}
