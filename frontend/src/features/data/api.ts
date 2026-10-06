import { useMutation, useQueryClient } from '@tanstack/react-query'
import { api } from '@/lib/api/client'
import type { ImportCommitResult, ImportMapping, ImportPreview, ImportRow, RestoreSummary } from '@/lib/api/types'
import { invalidateFinance } from '@/features/transactions/invalidate'

export function useImportPreview() {
  return useMutation({
    mutationFn: ({ file, accountId, mapping }: { file: File; accountId: number; mapping?: ImportMapping }) => {
      const form = new FormData()
      form.append('file', file)
      form.append('accountId', String(accountId))
      if (mapping) {
        const columns: [string, number | null][] = [['dateColumn', mapping.date], ['descriptionColumn', mapping.description],
          ['amountColumn', mapping.amount], ['debitColumn', mapping.debit], ['creditColumn', mapping.credit]]
        columns.forEach(([name, value]) => value !== null && form.append(name, String(value)))
        form.append('dateStyle', mapping.dateStyle)
        form.append('unit', mapping.unit)
        form.append('hasHeader', String(mapping.hasHeader))
      }
      return api.upload<ImportPreview>('/io/import/preview', form)
    },
    meta: { toastError: false },
  })
}

export function useImportCommit() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ accountId, rows }: { accountId: number; rows: ImportRow[] }) =>
      api.post<ImportCommitResult>('/io/import/commit', {
        accountId,
        rows: rows.map((r) => ({ date: r.date, amount: r.amount, description: r.description, categoryId: r.categoryId, ref: r.ref })),
      }),
    onSuccess: () => invalidateFinance(queryClient),
  })
}

/** Replaces all of the user's data; every cached view is stale afterwards. */
export function useRestoreBackup() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (file: File) => {
      const form = new FormData()
      form.append('file', file)
      form.append('confirm', 'REPLACE')
      return api.upload<RestoreSummary>('/io/restore', form)
    },
    meta: { toastError: false },
    onSuccess: () => queryClient.invalidateQueries(),
  })
}
