import { usePrefs } from '@/app/preferences'
import { toPersianNumerals } from '@/lib/persian/digits'

/** Plain text written by the AI (titles, labels), with its numbers in the user's digit style. */
export function useAiText() {
  const persian = usePrefs().digits === 'PERSIAN'
  return (text: string) => (persian ? toPersianNumerals(text) : text)
}
