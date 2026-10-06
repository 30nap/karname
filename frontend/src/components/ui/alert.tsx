import type { HTMLAttributes } from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '@/lib/cn'

const alertVariants = cva('flex gap-3 rounded-xl border px-4 py-3 text-sm [&>svg]:mt-0.5 [&>svg]:size-4 [&>svg]:shrink-0', {
  variants: {
    variant: {
      info: 'border-primary/20 bg-primary/5 text-foreground [&>svg]:text-primary',
      warning: 'border-warning/30 bg-warning/10 text-foreground [&>svg]:text-warning',
      destructive: 'border-destructive/30 bg-destructive/10 text-foreground [&>svg]:text-destructive',
    },
  },
  defaultVariants: { variant: 'info' },
})

export function Alert({ className, variant, ...props }: HTMLAttributes<HTMLDivElement> & VariantProps<typeof alertVariants>) {
  return <div role="alert" className={cn(alertVariants({ variant }), className)} {...props} />
}
