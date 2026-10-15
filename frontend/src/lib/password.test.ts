import { PASSWORD_TOO_LONG, passwordValidationError } from './password'

describe('password validation', () => {
  it.each(['a', 'م', '🔐'])('checks the UTF-8 boundary for %s', (character) => {
    const boundary = character.repeat(72 / new TextEncoder().encode(character).length)
    expect(passwordValidationError(boundary)).toBeUndefined()
    expect(passwordValidationError(boundary + character)).toBe(PASSWORD_TOO_LONG)
  })

  it('counts mixed text by bytes and still enforces the minimum length', () => {
    expect(passwordValidationError('a'.repeat(68) + '🔐')).toBeUndefined()
    expect(passwordValidationError('a'.repeat(69) + '🔐')).toBe(PASSWORD_TOO_LONG)
    expect(passwordValidationError('کوتاه')).toBe('رمز عبور باید حداقل ۸ کاراکتر باشد.')
  })
})
