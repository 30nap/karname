import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Direction } from 'radix-ui'
import { useEffect, useState, type ReactNode } from 'react'
import { Toaster } from 'sonner'
import { onUnauthorized } from '@/lib/api/client'
import { TooltipProvider } from '@/components/ui/tooltip'
import { authStatusKey } from '@/features/auth/api'
import type { AuthStatus } from '@/lib/api/types'
import { createQueryClient } from './queryClient'
import { ThemeSync } from './theme'

function SessionExpiryHandler({ client }: { client: QueryClient }) {
  useEffect(
    () =>
      onUnauthorized(() => {
        client.setQueryData<AuthStatus>(authStatusKey, (old) => ({
          authenticated: false,
          registrationOpen: old?.registrationOpen ?? true,
          hasUsers: true,
          user: null,
        }))
      }),
    [client],
  )
  return null
}

export function AppProviders({ children, client }: { children: ReactNode; client?: QueryClient }) {
  const [queryClient] = useState(() => client ?? createQueryClient())
  return (
    <QueryClientProvider client={queryClient}>
      <Direction.Provider dir="rtl">
        <TooltipProvider delayDuration={300}>
          <ThemeSync />
          <SessionExpiryHandler client={queryClient} />
          {children}
          <Toaster dir="rtl" position="top-center" richColors closeButton toastOptions={{ style: { fontFamily: 'inherit' } }} />
        </TooltipProvider>
      </Direction.Provider>
    </QueryClientProvider>
  )
}
