import { useRouteError } from 'react-router'
import { ErrorScreen } from './ErrorScreen'

/** Shown when a page fails to load (e.g. a stale chunk after an update) or throws while rendering. */
export function RouteError() {
  const error = useRouteError()
  console.error(error)
  return <ErrorScreen message="بارگذاری صفحه با خطا مواجه شد. صفحه را تازه کنید." onRetry={() => window.location.reload()} />
}
