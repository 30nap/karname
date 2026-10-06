import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import { ApiError } from '@/lib/api/client'

function shouldRetry(failureCount: number, error: unknown) {
  if (error instanceof ApiError && error.status > 0 && error.status < 500) return false
  return failureCount < 2
}

export function createQueryClient() {
  return new QueryClient({
    queryCache: new QueryCache(),
    mutationCache: new MutationCache({
      onError: (error, _vars, _ctx, mutation) => {
        // Mutations show their own errors unless they opt in to the global toast.
        if (mutation.meta?.toastError === false) return
        if (error instanceof ApiError && error.fieldErrors.length > 0) return
        toast.error(error instanceof Error ? error.message : 'خطایی رخ داد.')
      },
    }),
    defaultOptions: {
      queries: { staleTime: 30_000, retry: shouldRetry, refetchOnWindowFocus: true },
      mutations: { retry: false },
    },
  })
}
