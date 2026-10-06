import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api, query } from '@/lib/api/client'
import type { Anomaly, CategoryReport, MonthTotals, ReportKind, TopItem } from '@/lib/api/types'

export function useMonthlyReport(months = 12) {
  return useQuery({ queryKey: ['reports', 'monthly', months], queryFn: () => api.get<MonthTotals[]>(`/reports/monthly${query({ months })}`) })
}

export function useCategoryReport(month: string, span: number, kind: ReportKind, enabled = true) {
  return useQuery({
    queryKey: ['reports', 'categories', month, span, kind],
    queryFn: () => api.get<CategoryReport>(`/reports/categories${query({ month, span, kind })}`),
    placeholderData: keepPreviousData,
    enabled,
  })
}

export function useTopReport(month: string, span: number, kind: ReportKind, limit = 10, enabled = true) {
  return useQuery({
    queryKey: ['reports', 'top', month, span, kind, limit],
    queryFn: () => api.get<TopItem[]>(`/reports/top${query({ month, span, kind, limit })}`),
    placeholderData: keepPreviousData,
    enabled,
  })
}

export function useAnomalies(month: string, enabled = true) {
  return useQuery({
    queryKey: ['reports', 'anomalies', month],
    queryFn: () => api.get<Anomaly[]>(`/reports/anomalies${query({ month })}`),
    enabled,
  })
}
