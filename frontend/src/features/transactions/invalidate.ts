import type { QueryClient } from '@tanstack/react-query'

/** Anything that changes money movements invalidates every derived view. */
export function invalidateFinance(queryClient: QueryClient) {
  return Promise.all(
    ['transactions', 'accounts', 'dashboard', 'net-worth', 'commodities', 'prices', 'cost-basis', 'reports', 'budgets', 'goals',
      'loans', 'recurring', 'cheques', 'forecast', 'notifications'].map((key) =>
      queryClient.invalidateQueries({ queryKey: [key] }),
    ),
  )
}
