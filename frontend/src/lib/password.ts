export const PASSWORD_LENGTH_HINT = 'حداقل ۸ کاراکتر؛ حداکثر ۷۲ بایت (مثلاً ۷۲ حرف انگلیسی یا ۳۶ حرف فارسی).'
export const PASSWORD_TOO_LONG = 'رمز عبور طولانی است؛ حداکثر ۷۲ بایت (مثلاً ۷۲ حرف انگلیسی یا ۳۶ حرف فارسی).'

/** Keep new passwords within the server's BCrypt UTF-8 byte limit. */
export function passwordValidationError(password: string): string | undefined {
  if (password.length < 8) return 'رمز عبور باید حداقل ۸ کاراکتر باشد.'
  if (new TextEncoder().encode(password).length > 72) return PASSWORD_TOO_LONG
}
