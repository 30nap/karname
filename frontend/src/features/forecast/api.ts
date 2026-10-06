import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { api } from '@/lib/api/client'
import type { Forecast } from '@/lib/api/types'

export function useForecast(days = 90) {
  return useQuery({ queryKey: ['forecast', days], queryFn: () => api.get<Forecast>(`/forecast?days=${days}`), placeholderData: keepPreviousData })
}
