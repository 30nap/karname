import { useId, type ReactElement, type ReactNode, cloneElement, isValidElement } from 'react'
import { Label } from './label'
import { cn } from '@/lib/cn'

interface FormFieldProps {
  label: string
  error?: string
  hint?: ReactNode
  className?: string
  children: ReactElement<{ id?: string; 'aria-invalid'?: boolean; 'aria-describedby'?: string }>
  optional?: boolean
}

/** Label + control + hint/error, wiring ids and aria attributes. */
export function FormField({ label, error, hint, className, children, optional }: FormFieldProps) {
  const id = useId()
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined
  const control = isValidElement(children)
    ? cloneElement(children, { id: children.props.id ?? id, 'aria-invalid': error ? true : undefined, 'aria-describedby': describedBy })
    : children
  return (
    <div className={cn('flex flex-col gap-1.5', className)}>
      <Label htmlFor={children.props.id ?? id}>
        {label}
        {optional ? <span className="ms-1 text-xs font-normal text-muted-foreground">(اختیاری)</span> : null}
      </Label>
      {control}
      {error ? (
        <p id={`${id}-error`} className="text-xs text-destructive" role="alert">{error}</p>
      ) : hint ? (
        <div id={`${id}-hint`} className="text-xs text-muted-foreground">{hint}</div>
      ) : null}
    </div>
  )
}
