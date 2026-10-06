import { useId, type ReactElement, type ReactNode, cloneElement, isValidElement } from 'react'
import { Label } from './label'
import { FieldContext } from './field-context'
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
  const controlId = children.props.id ?? id
  const controlProps = { id: controlId, 'aria-invalid': error ? true : undefined, 'aria-describedby': describedBy }
  const control = isValidElement(children) ? cloneElement(children, controlProps) : children
  return (
    <div className={cn('flex flex-col gap-1.5', className)}>
      <Label htmlFor={controlId}>
        {label}
        {optional ? <span className="ms-1 text-xs font-normal text-muted-foreground">(اختیاری)</span> : null}
      </Label>
      <FieldContext.Provider value={controlProps}>{control}</FieldContext.Provider>
      {error ? (
        <p id={`${id}-error`} className="text-xs text-destructive" role="alert">{error}</p>
      ) : hint ? (
        <div id={`${id}-hint`} className="text-xs text-muted-foreground">{hint}</div>
      ) : null}
    </div>
  )
}
