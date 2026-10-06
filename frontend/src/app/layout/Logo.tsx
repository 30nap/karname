import { cn } from '@/lib/cn'

export function Logo({ compact, className }: { compact?: boolean; className?: string }) {
  return (
    <span className={cn('flex items-center gap-2', className)}>
      <svg viewBox="0 0 64 64" className="size-8 shrink-0" aria-hidden>
        <rect width="64" height="64" rx="14" className="fill-primary" />
        <rect x="15" y="34" width="9" height="15" rx="2.5" className="fill-primary-foreground" opacity="0.75" />
        <rect x="27.5" y="26" width="9" height="23" rx="2.5" className="fill-primary-foreground" opacity="0.9" />
        <rect x="40" y="16" width="9" height="33" rx="2.5" className="fill-primary-foreground" />
      </svg>
      {compact ? null : <span className="text-lg font-bold">کارنامه</span>}
    </span>
  )
}
