import { cn } from '@/lib/cn'

export function Spinner({ className, label = 'در حال بارگذاری' }: { className?: string; label?: string }) {
  return (
    <span role="status" aria-label={label} className={cn('inline-block size-5 animate-spin rounded-full border-2 border-current border-e-transparent text-primary', className)} />
  )
}

export function PageSpinner() {
  return (
    <div className="flex min-h-[40dvh] items-center justify-center">
      <Spinner className="size-7" />
    </div>
  )
}
