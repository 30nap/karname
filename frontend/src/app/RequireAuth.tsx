import { Navigate, Outlet, useLocation } from 'react-router'
import { PageSpinner } from '@/components/ui/spinner'
import { useAuthStatus } from '@/features/auth/api'
import { ErrorScreen } from './ErrorScreen'

export function RequireAuth() {
  const { data, isPending, isError, refetch } = useAuthStatus()
  const location = useLocation()
  if (isPending) return <PageSpinner />
  if (isError) return <ErrorScreen onRetry={() => refetch()} />
  if (!data.authenticated) {
    const target = data.hasUsers ? '/login' : '/register'
    return <Navigate to={target} replace state={{ from: location.pathname }} />
  }
  return <Outlet />
}

/** Login/register pages redirect signed-in users to the app. */
export function PublicOnly() {
  const { data, isPending } = useAuthStatus()
  if (isPending) return <PageSpinner />
  if (data?.authenticated) return <Navigate to="/" replace />
  return <Outlet />
}
