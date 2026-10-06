/** Error thrown for non-2xx API responses; carries the backend's Persian message and code. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly fieldErrors: { field: string; message: string }[]

  constructor(status: number, body: unknown) {
    const problem = (body && typeof body === 'object' ? body : {}) as {
      detail?: string
      code?: string
      errors?: { field: string; message: string }[]
    }
    super(problem.detail || defaultMessage(status))
    this.name = 'ApiError'
    this.status = status
    this.code = problem.code ?? `http.${status}`
    this.fieldErrors = Array.isArray(problem.errors) ? problem.errors : []
  }
}

function defaultMessage(status: number): string {
  if (status === 0) return 'ارتباط با سرور برقرار نشد. اتصال اینترنت را بررسی کنید.'
  if (status === 401) return 'لطفاً وارد حساب کاربری شوید.'
  if (status === 403) return 'اجازه‌ی دسترسی به این بخش را ندارید.'
  if (status === 404) return 'موردی پیدا نشد.'
  if (status >= 500) return 'خطای داخلی سرور رخ داد. لطفاً دوباره تلاش کنید.'
  return 'درخواست با خطا مواجه شد.'
}

export function readCookie(name: string): string | null {
  const match = document.cookie.split('; ').find((c) => c.startsWith(`${name}=`))
  return match ? decodeURIComponent(match.slice(name.length + 1)) : null
}

type Listener = (error: ApiError) => void
const unauthorizedListeners = new Set<Listener>()

/** Lets the auth layer react to a session that expired while the app was open. */
export function onUnauthorized(listener: Listener): () => void {
  unauthorizedListeners.add(listener)
  return () => unauthorizedListeners.delete(listener)
}

export interface RequestOptions {
  signal?: AbortSignal
  /** Do not broadcast 401s (used by the auth bootstrap itself). */
  silent401?: boolean
}

export const API_BASE = '/api/v1'

export async function apiRequest<T>(method: string, path: string, body?: unknown, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  // FormData sets its own multipart boundary
  const form = body instanceof FormData
  if (body !== undefined && !form) headers['Content-Type'] = 'application/json'
  if (method !== 'GET' && method !== 'HEAD') {
    const token = readCookie('XSRF-TOKEN')
    if (token) headers['X-XSRF-TOKEN'] = token
  }
  let response: Response
  try {
    response = await fetch(API_BASE + path, {
      method,
      headers,
      body: body === undefined ? undefined : form ? body : JSON.stringify(body),
      credentials: 'same-origin',
      signal: options.signal,
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new ApiError(0, null)
  }
  if (response.status === 204) return undefined as T
  const isJson = response.headers.get('content-type')?.includes('json')
  const data = isJson ? await response.json().catch(() => null) : await response.text().catch(() => null)
  if (!response.ok) {
    const error = new ApiError(response.status, data)
    if (response.status === 401 && !options.silent401) unauthorizedListeners.forEach((l) => l(error))
    throw error
  }
  return data as T
}

export const api = {
  get: <T>(path: string, options?: RequestOptions) => apiRequest<T>('GET', path, undefined, options),
  post: <T>(path: string, body?: unknown, options?: RequestOptions) => apiRequest<T>('POST', path, body ?? {}, options),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) => apiRequest<T>('PUT', path, body ?? {}, options),
  patch: <T>(path: string, body?: unknown, options?: RequestOptions) => apiRequest<T>('PATCH', path, body ?? {}, options),
  delete: <T>(path: string, body?: unknown, options?: RequestOptions) => apiRequest<T>('DELETE', path, body, options),
  /** Multipart POST, for file uploads. */
  upload: <T>(path: string, form: FormData, options?: RequestOptions) => apiRequest<T>('POST', path, form, options),
}

/** Builds a query string, skipping empty values. */
export function query(params: Record<string, string | number | boolean | null | undefined | string[]>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue
    if (Array.isArray(value)) value.forEach((v) => search.append(key, v))
    else search.append(key, String(value))
  }
  const s = search.toString()
  return s ? `?${s}` : ''
}
