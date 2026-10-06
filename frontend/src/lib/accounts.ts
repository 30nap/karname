import type { Account, AccountType } from './api/types'

const EVERYDAY: AccountType[] = ['BANK', 'CASH', 'EWALLET']

/** Accounts Toman can be paid from or into (cash, bank, e-wallet…), not debts or other units. */
export const isTomanAsset = (account: Account) => account.commodity === 'IRT' && !account.liability

/** The account a new payment most likely goes through: the first Toman bank, cash or e-wallet account. */
export function everydayAccount(accounts: Account[]): Account | undefined {
  return accounts.find((a) => EVERYDAY.includes(a.type) && a.commodity === 'IRT')
}
