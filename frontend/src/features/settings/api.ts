import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api/client'
import type { AdminUser, AuthStatus, Me, Role } from '@/lib/api/types'
import { authStatusKey } from '@/features/auth/api'

export function useChangePassword() {
  return useMutation({
    mutationFn: (input: { currentPassword: string; newPassword: string }) => api.put<void>('/me/password', input),
    meta: { toastError: false },
  })
}

export function useUpdateProfile() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (displayName: string) => api.patch<Me>('/me', { displayName }),
    onSuccess: (me) => queryClient.setQueryData<AuthStatus>(authStatusKey, (old) => (old ? { ...old, user: me } : old)),
  })
}

export function useRevokeOtherSessions() {
  return useMutation({ mutationFn: () => api.post<void>('/me/sessions/revoke-others') })
}

export function useTotpSetup() {
  return useMutation({ mutationFn: () => api.post<{ secret: string; otpauthUri: string }>('/me/totp/setup') })
}

export function useTotpEnable() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (code: string) => api.post<{ recoveryCodes: string[] }>('/me/totp/enable', { code }),
    meta: { toastError: false },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: authStatusKey }),
  })
}

export function useTotpDisable() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (password: string) => api.post<void>('/me/totp/disable', { password }),
    meta: { toastError: false },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: authStatusKey }),
  })
}

export function useDeleteAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (password: string) => api.delete<void>('/me', { password }),
    meta: { toastError: false },
    onSuccess: async () => {
      queryClient.clear()
      await queryClient.invalidateQueries({ queryKey: authStatusKey })
    },
  })
}

const adminUsersKey = ['admin', 'users'] as const

export function useAdminUsers(enabled: boolean) {
  return useQuery({ queryKey: adminUsersKey, queryFn: () => api.get<AdminUser[]>('/admin/users'), enabled })
}

export function useUpdateAdminUser() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: { id: number; enabled?: boolean; role?: Role }) => api.patch<AdminUser>(`/admin/users/${id}`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminUsersKey }),
  })
}

export function useResetUserPassword() {
  return useMutation({
    mutationFn: ({ id, newPassword }: { id: number; newPassword: string }) => api.post<void>(`/admin/users/${id}/reset-password`, { newPassword }),
  })
}

export function useDeleteAdminUser() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete<void>(`/admin/users/${id}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: adminUsersKey }),
  })
}

const systemKey = ['admin', 'system'] as const

export function useSystemSettings(enabled: boolean) {
  return useQuery({ queryKey: systemKey, queryFn: () => api.get<{ registrationOpen: boolean }>('/admin/system'), enabled })
}

export function useUpdateSystemSettings() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { registrationOpen: boolean }) => api.put<{ registrationOpen: boolean }>('/admin/system', body),
    onSuccess: (data) => {
      queryClient.setQueryData(systemKey, data)
      queryClient.invalidateQueries({ queryKey: authStatusKey })
    },
  })
}
