import { AlertDialog } from 'radix-ui'
import type { ReactNode } from 'react'
import { Button } from './button'

export function ConfirmDialog({ open, onOpenChange, title, description, confirmLabel = 'تأیید', destructive, loading, onConfirm }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: ReactNode
  confirmLabel?: string
  destructive?: boolean
  loading?: boolean
  onConfirm: () => void
}) {
  return (
    <AlertDialog.Root open={open} onOpenChange={onOpenChange}>
      <AlertDialog.Portal>
        <AlertDialog.Overlay className="kn-overlay fixed inset-0 z-50 bg-black/40" />
        <AlertDialog.Content className="kn-dialog-content fixed inset-x-0 bottom-0 z-50 rounded-t-2xl border-t bg-card p-5 shadow-xl sm:inset-x-auto sm:bottom-auto sm:left-1/2 sm:top-1/2 sm:w-full sm:max-w-md sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-2xl sm:border safe-bottom">
          <AlertDialog.Title className="text-lg font-semibold">{title}</AlertDialog.Title>
          {description ? <AlertDialog.Description className="mt-2 text-sm text-muted-foreground">{description}</AlertDialog.Description> : null}
          <div className="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <AlertDialog.Cancel asChild>
              <Button variant="outline">انصراف</Button>
            </AlertDialog.Cancel>
            <Button variant={destructive ? 'destructive' : 'default'} loading={loading} onClick={onConfirm}>
              {confirmLabel}
            </Button>
          </div>
        </AlertDialog.Content>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  )
}
