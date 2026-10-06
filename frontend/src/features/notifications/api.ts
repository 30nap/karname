import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api, query } from '@/lib/api/client'
import type { AppNotification } from '@/lib/api/types'

export function useNotificationCount() {
  return useQuery({
    queryKey: ['notifications', 'count'],
    queryFn: () => api.get<{ unread: number }>('/notifications/count'),
    refetchInterval: 5 * 60_000,
    refetchOnWindowFocus: true,
    meta: { toastError: false },
  })
}

export function useNotifications(enabled: boolean) {
  return useQuery({ queryKey: ['notifications', 'list'], queryFn: () => api.get<AppNotification[]>(`/notifications${query({ unread: false })}`), enabled })
}

export function useMarkRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number | 'all') => (id === 'all' ? api.post<void>('/notifications/read-all') : api.post<void>(`/notifications/${id}/read`)),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['notifications'] }),
  })
}
