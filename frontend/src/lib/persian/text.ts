const TATWEEL = 0x0640
const SUPERSCRIPT_ALEF = 0x0670

function isRemovable(code: number) {
  // Tatweel and Arabic diacritics (harakat) carry no meaning for matching.
  return code === TATWEEL || code === SUPERSCRIPT_ALEF || (code >= 0x064b && code <= 0x065f)
}

/** Unifies Arabic letter variants with Persian ones and normalizes digits, for searching. */
export function normalizePersian(text: string): string {
  let out = ''
  for (const ch of text) {
    const code = ch.charCodeAt(0)
    if (isRemovable(code)) continue
    if (code === 0x064a || code === 0x0649) out += 'ی' // Arabic Yeh, Alef Maksura
    else if (code === 0x0643) out += 'ک' // Arabic Kaf
    else if (code === 0x0629) out += 'ه' // Teh Marbuta
    else if (code >= 0x06f0 && code <= 0x06f9) out += String(code - 0x06f0)
    else if (code >= 0x0660 && code <= 0x0669) out += String(code - 0x0660)
    else out += ch
  }
  return out
}

export function normalizeForSearch(text: string): string {
  return normalizePersian(text).replace(/‌/g, ' ').replace(/\s+/g, ' ').trim().toLowerCase()
}
