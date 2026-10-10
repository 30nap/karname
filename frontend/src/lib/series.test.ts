import { describe, expect, it } from 'vitest'
import { fromFirstData } from './series'

describe('fromFirstData', () => {
  const hasData = (v: number) => v !== 0

  it('drops the empty rows before the first with data, and only those', () => {
    expect(fromFirstData([0, 0, 5, 0, 7], hasData)).toEqual([5, 0, 7])
  })

  it('keeps everything when the first row has data', () => {
    expect(fromFirstData([1, 0, 2], hasData)).toEqual([1, 0, 2])
  })

  it('returns nothing when no row has data', () => {
    expect(fromFirstData([0, 0], hasData)).toEqual([])
    expect(fromFirstData([], hasData)).toEqual([])
  })
})
