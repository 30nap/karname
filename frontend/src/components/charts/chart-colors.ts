/** CSS color of a categorical chart slot (1..8); slots are assigned per entity, never by rank. */
export function chartColor(slot: number): string {
  return `var(--chart-${slot})`
}

export const AXIS_TICK = { fill: 'var(--color-muted-foreground)', fontSize: 11, fontFamily: 'inherit' } as const
