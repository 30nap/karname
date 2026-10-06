import { render } from '@testing-library/react'
import { QueryClient } from '@tanstack/react-query'
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router'
import type { ReactElement } from 'react'
import { AppProviders } from '@/app/providers'

export function createTestQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } })
}

type Handler = (url: string, init: RequestInit) => { status?: number; body?: unknown } | undefined

/** Stubs fetch with a simple router: handlers receive the path (without /api/v1) and request init. */
export function stubApi(handler: Handler) {
  const fn = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = String(input).replace('/api/v1', '')
    const result = handler(url, init) ?? { status: 404, body: { detail: 'not mocked: ' + url } }
    const status = result.status ?? 200
    return new Response(status === 204 ? null : JSON.stringify(result.body ?? {}), {
      status,
      headers: { 'content-type': 'application/json' },
    })
  })
  vi.stubGlobal('fetch', fn)
  return fn
}

export function renderRoutes(routes: RouteObject[], initialPath = '/') {
  const router = createMemoryRouter(routes, { initialEntries: [initialPath] })
  const client = createTestQueryClient()
  const utils = render(
    <AppProviders client={client}>
      <RouterProvider router={router} />
    </AppProviders>,
  )
  return { ...utils, router, client }
}

export function renderWithProviders(ui: ReactElement) {
  return renderRoutes([{ path: '/', element: ui }])
}
