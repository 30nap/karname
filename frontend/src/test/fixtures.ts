import type { Account, Category, Commodity, Me } from '@/lib/api/types'

export function makeMe(overrides: Partial<Me['settings']> = {}): Me {
  return {
    id: 1, username: 'sina', displayName: 'سینا', role: 'ADMIN', totpEnabled: false,
    settings: {
      displayUnit: 'TOMAN', digitStyle: 'PERSIAN', theme: 'LIGHT', wealthUnits: ['USD', 'GOLD18'], inflationRate: null,
      aiEnabled: true, aiShareDescriptions: true, ...overrides,
    },
  }
}

export function authStatus(me: Me = makeMe()) {
  return { authenticated: true, registrationOpen: false, hasUsers: true, user: me }
}

export const COMMODITIES: Commodity[] = [
  { code: 'IRT', nameFa: 'تومان', unitFa: 'تومان', kind: 'TOMAN', scale: 0, custom: false, latestPrice: null },
  { code: 'USD', nameFa: 'دلار آمریکا', unitFa: 'دلار', kind: 'FIAT', scale: 2, custom: false,
    latestPrice: { priceToman: '100000', pricedAt: '2026-10-06T08:00:00Z', source: 'MANUAL', personal: true, stale: false } },
]

export function makeAccount(overrides: Partial<Account>): Account {
  return {
    id: 1, name: 'کارت ملت', type: 'BANK', liability: false, commodity: 'IRT', bank: 'MELLAT', identifierHints: [], counterparty: null,
    icon: null, includeInNetWorth: true, archived: false, notes: null, sortOrder: 0, balance: '5000000', valueToman: '5000000',
    priced: true, priceStale: false, createdAt: '2026-04-01T00:00:00Z', ...overrides,
  }
}

export const CATEGORIES: Category[] = [
  { id: 1, parentId: null, kind: 'EXPENSE', name: 'خوراک', icon: 'utensils', systemKey: null, archived: false, sortOrder: 0 },
  { id: 2, parentId: 1, kind: 'EXPENSE', name: 'رستوران', icon: null, systemKey: null, archived: false, sortOrder: 1 },
  { id: 3, parentId: null, kind: 'EXPENSE', name: 'حمل‌ونقل', icon: 'car', systemKey: null, archived: false, sortOrder: 2 },
  { id: 4, parentId: 3, kind: 'EXPENSE', name: 'تاکسی اینترنتی', icon: null, systemKey: null, archived: false, sortOrder: 3 },
  { id: 5, parentId: null, kind: 'EXPENSE', name: 'کارمزد بانکی', icon: 'landmark', systemKey: 'bank_fees', archived: false, sortOrder: 4 },
  { id: 6, parentId: null, kind: 'INCOME', name: 'حقوق', icon: 'briefcase', systemKey: 'salary', archived: false, sortOrder: 5 },
]
