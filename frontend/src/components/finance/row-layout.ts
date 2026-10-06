/**
 * List row in two lines: icon | title over details | amount over actions. From the sm breakpoint the amount
 * and the actions move beside both lines, so wide screens read as one row and narrow ones keep titles whole.
 */
export const ROW = 'grid grid-cols-[2.5rem_minmax(0,1fr)_auto] items-center gap-x-3 gap-y-1 py-3 sm:grid-cols-[2.5rem_minmax(0,1fr)_auto_auto]'
export const ROW_ICON = 'row-span-2 self-start sm:self-center'
export const ROW_AMOUNT = 'text-end sm:col-start-3 sm:row-span-2 sm:row-start-1'
export const ROW_DETAILS = 'sm:col-start-2 sm:row-start-2'
export const ROW_ACTIONS = 'flex items-center justify-end gap-1 sm:col-start-4 sm:row-span-2 sm:row-start-1'
