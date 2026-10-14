import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo } from 'react'
import { api, query } from '@/lib/api/client'
import type { Commodity, CommodityKind, PriceRecord, SecurityInstrument } from '@/lib/api/types'
import { invalidateFinance } from '@/features/transactions/invalidate'

export const commoditiesKey = ['commodities'] as const

export function useCommodities() {
  return useQuery({ queryKey: commoditiesKey, queryFn: () => api.get<Commodity[]>('/commodities'), staleTime: 60_000 })
}

/** Commodity lookup by code (Toman fallback while loading). */
export function useCommodityMap() {
  const { data } = useCommodities()
  return useMemo(() => {
    const map = new Map<string, Commodity>()
    data?.forEach((c) => map.set(c.code, c))
    return {
      list: data ?? [],
      get: (code: string): Commodity =>
        map.get(code) ?? { code, nameFa: code, unitFa: code === 'IRT' ? 'تومان' : code, kind: code === 'IRT' ? 'TOMAN' : 'OTHER', scale: code === 'IRT' ? 0 : 2, custom: false, latestPrice: null },
    }
  }, [data])
}

export function usePriceHistory(code: string | null) {
  return useQuery({
    queryKey: ['prices', code],
    queryFn: () => api.get<PriceRecord[]>(`/prices${query({ commodity: code, limit: 200 })}`),
    enabled: !!code,
  })
}

export function useRecordPrice() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: { commodity: string; priceToman: string; pricedAt?: string; global?: boolean }) =>
      api.post<PriceRecord>('/prices', input),
    onSuccess: () => invalidateFinance(queryClient),
  })
}

export function useDeletePrice() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: number) => api.delete<void>(`/prices/${id}`),
    onSuccess: () => invalidateFinance(queryClient),
  })
}

export function useSaveCustomCommodity() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ code, ...body }: { code?: string; nameFa: string; unitFa: string; kind: CommodityKind; scale: number }) =>
      code ? api.put<Commodity>(`/commodities/${code}`, body) : api.post<Commodity>('/commodities', body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: commoditiesKey }),
  })
}

export function useDeleteCustomCommodity() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (code: string) => api.delete<void>(`/commodities/${code}`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: commoditiesKey }),
  })
}

export function useSearchSecurities(term: string | null) {
  return useQuery({
    queryKey: ['securities', 'search', term],
    queryFn: ({ signal }) => api.get<SecurityInstrument[]>(`/securities/search${query({ q: term })}`, { signal }),
    enabled: !!term && term.length >= 2,
    staleTime: 5 * 60_000,
    retry: false,
  })
}

export function useSaveSecurity() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ code, instrumentId }: { code?: string; instrumentId: string }) =>
      code ? api.put<Commodity>(`/commodities/${code}/security`, { instrumentId }) : api.post<Commodity>('/securities', { instrumentId }),
    meta: { toastError: false },
    onSuccess: () => invalidateFinance(queryClient),
  })
}

export function useRefreshSecurity() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (code: string) => api.post<Commodity>(`/commodities/${code}/security/refresh`),
    meta: { toastError: false },
    onSuccess: () => invalidateFinance(queryClient),
  })
}

export function useUnlinkSecurity() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (code: string) => api.delete<Commodity>(`/commodities/${code}/security`),
    meta: { toastError: false },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: commoditiesKey }),
  })
}
