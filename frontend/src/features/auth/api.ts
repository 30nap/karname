import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api/client'
import type { AuthStatus, Me, Settings } from '@/lib/api/types'

export const authStatusKey = ['auth', 'status'] as const

export function useAuthStatus() {
  return useQuery({
    queryKey: authStatusKey,
    queryFn: () => api.get<AuthStatus>('/auth/status', { silent401: true }),
    staleTime: 60_000,
    retry: 1,
  })
}

/** The signed-in user; only valid inside RequireAuth. */
export function useMe(): Me {
  const { data } = useAuthStatus()
  if (!data?.user) throw new Error('useMe() used outside an authenticated route')
  return data.user
}

export function useSetMe() {
  const queryClient = useQueryClient()
  return (user: Me | null) =>
    queryClient.setQueryData<AuthStatus>(authStatusKey, (old) => ({
      authenticated: !!user,
      registrationOpen: old?.registrationOpen ?? true,
      hasUsers: true,
      user,
    }))
}

export interface LoginInput {
  username: string
  password: string
  totpCode?: string
  rememberMe: boolean
}

export function useLogin() {
  const setMe = useSetMe()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: LoginInput) => api.post<Me>('/auth/login', input),
    meta: { toastError: false },
    onSuccess: (me) => {
      queryClient.removeQueries({ predicate: (q) => q.queryKey[0] !== 'auth' })
      setMe(me)
    },
  })
}

export interface RegisterInput {
  username: string
  displayName: string
  password: string
  rememberMe: boolean
}

export function useRegister() {
  const setMe = useSetMe()
  return useMutation({
    mutationFn: (input: RegisterInput) => api.post<Me>('/auth/register', input),
    meta: { toastError: false },
    onSuccess: (me) => setMe(me),
  })
}

export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<void>('/auth/logout'),
    onSettled: async () => {
      queryClient.clear()
      await queryClient.invalidateQueries({ queryKey: authStatusKey })
    },
  })
}

export function useUpdateSettings() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (settings: Settings) => api.put<Settings>('/settings', settings),
    onSuccess: (settings) => {
      queryClient.setQueryData<AuthStatus>(authStatusKey, (old) =>
        old?.user ? { ...old, user: { ...old.user, settings } } : old,
      )
    },
  })
}
