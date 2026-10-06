import { createContext, useContext } from 'react'

export interface FieldControlProps {
  id: string
  'aria-invalid'?: boolean
  'aria-describedby'?: string
}

/**
 * Lets composite controls (e.g. a Radix Select, whose root renders no element) put the field's id
 * and aria attributes on their focusable part, so the label is announced with it.
 */
export const FieldContext = createContext<FieldControlProps | null>(null)

export function useFieldControl(): FieldControlProps | null {
  return useContext(FieldContext)
}
