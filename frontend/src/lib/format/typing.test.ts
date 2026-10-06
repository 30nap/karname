import { formatTyping, normalizeTyping } from './typing'

describe('normalizeTyping', () => {
  it('accepts Persian and Arabic digits and both separator styles', () => {
    expect(normalizeTyping('۱٬۲۳۴٫۵', false)).toBe('1234.5')
    expect(normalizeTyping('١٢٣', false)).toBe('123')
    expect(normalizeTyping('1,234', false)).toBe('1234')
    expect(normalizeTyping('۱۲/۵', false)).toBe('12.5')
  })

  it('rejects letters and a second decimal point', () => {
    expect(normalizeTyping('12a', false)).toBeNull()
    expect(normalizeTyping('1.2.3', false)).toBeNull()
  })

  it('accepts a minus sign only when allowed', () => {
    expect(normalizeTyping('-5', false)).toBeNull()
    expect(normalizeTyping('−5', true)).toBe('-5')
  })
})

describe('formatTyping', () => {
  it('groups thousands with Persian separators and digits', () => {
    expect(formatTyping('1234567', true)).toBe('۱٬۲۳۴٬۵۶۷')
    expect(formatTyping('1234567', false)).toBe('1,234,567')
  })

  it('keeps a trailing decimal point and strips leading zeros while typing', () => {
    expect(formatTyping('1234.', true)).toBe('۱٬۲۳۴٫')
    expect(formatTyping('0012', false)).toBe('12')
    expect(formatTyping('.5', false)).toBe('0.5')
    expect(formatTyping('-1000', false)).toBe('-1,000')
  })
})
