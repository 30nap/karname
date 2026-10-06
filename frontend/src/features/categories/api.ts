import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo } from 'react'
import { api, query } from '@/lib/api/client'
import type { Category, CategoryKind } from '@/lib/api/types'
import { invalidateFinance } from '@/features/transactions/invalidate'

export const categoriesKey = ['categories'] as const

export function useCategories() {
  return useQuery({ queryKey: categoriesKey, queryFn: () => api.get<Category[]>('/categories'), staleTime: 60_000 })
}

export interface CategoryNode extends Category {
  children: Category[]
}

/** Categories of one kind as a two-level tree, archived ones removed unless asked. */
export function useCategoryTree(kind: CategoryKind, includeArchived = false) {
  const { data } = useCategories()
  return useMemo(() => {
    const list = (data ?? []).filter((c) => c.kind === kind && (includeArchived || !c.archived))
    const roots: CategoryNode[] = list.filter((c) => c.parentId === null).map((c) => ({ ...c, children: [] }))
    const byId = new Map(roots.map((r) => [r.id, r]))
    list.filter((c) => c.parentId !== null).forEach((c) => byId.get(c.parentId!)?.children.push(c))
    return roots
  }, [data, kind, includeArchived])
}

export function useSaveCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, ...body }: { id?: number; name: string; kind: CategoryKind; parentId?: number | null; icon?: string | null; archived?: boolean }) =>
      id ? api.put<Category>(`/categories/${id}`, body) : api.post<Category>('/categories', body),
    meta: { toastError: false },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: categoriesKey }),
  })
}

export function useDeleteCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, reassignTo }: { id: number; reassignTo?: number | null }) =>
      api.delete<void>(`/categories/${id}${query({ reassignTo: reassignTo ?? undefined })}`),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: categoriesKey })
      await invalidateFinance(queryClient)
    },
  })
}

export async function suggestCategory(description: string): Promise<number | null> {
  if (description.trim().length < 2) return null
  const result = await api.get<{ categoryId: number | null }>(`/categories/suggest${query({ description })}`)
  return result.categoryId
}
