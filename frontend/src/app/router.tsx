import { createBrowserRouter, type RouteObject } from 'react-router'
import { AppShell } from './layout/AppShell'
import { PublicOnly, RequireAuth } from './RequireAuth'
import { NotFoundPage } from './NotFoundPage'
import { RouteError } from './RouteError'

export const routes: RouteObject[] = [
  {
    errorElement: <RouteError />,
    children: [
      {
        element: <PublicOnly />,
        children: [
          { path: '/login', lazy: () => import('@/features/auth/LoginPage').then((m) => ({ Component: m.LoginPage })) },
          { path: '/register', lazy: () => import('@/features/auth/RegisterPage').then((m) => ({ Component: m.RegisterPage })) },
        ],
      },
      {
        element: <RequireAuth />,
        children: [
          {
            element: <AppShell />,
            children: [
              { index: true, lazy: () => import('@/features/dashboard/DashboardPage').then((m) => ({ Component: m.DashboardPage })) },
              { path: 'transactions', lazy: () => import('@/features/transactions/TransactionsPage').then((m) => ({ Component: m.TransactionsPage })) },
              { path: 'accounts', lazy: () => import('@/features/accounts/AccountsPage').then((m) => ({ Component: m.AccountsPage })) },
              { path: 'accounts/:id', lazy: () => import('@/features/accounts/AccountDetailPage').then((m) => ({ Component: m.AccountDetailPage })) },
              { path: 'budgets', lazy: () => import('@/features/budgets/BudgetsPage').then((m) => ({ Component: m.BudgetsPage })) },
              { path: 'reports', lazy: () => import('@/features/reports/ReportsPage').then((m) => ({ Component: m.ReportsPage })) },
              { path: 'goals', lazy: () => import('@/features/goals/GoalsPage').then((m) => ({ Component: m.GoalsPage })) },
              { path: 'loans', lazy: () => import('@/features/loans/LoansPage').then((m) => ({ Component: m.LoansPage })) },
              { path: 'loans/:id', lazy: () => import('@/features/loans/LoanDetailPage').then((m) => ({ Component: m.LoanDetailPage })) },
              { path: 'recurring', lazy: () => import('@/features/recurring/RecurringPage').then((m) => ({ Component: m.RecurringPage })) },
              { path: 'cheques', lazy: () => import('@/features/cheques/ChequesPage').then((m) => ({ Component: m.ChequesPage })) },
              { path: 'assets', lazy: () => import('@/features/commodities/AssetsPage').then((m) => ({ Component: m.AssetsPage })) },
              { path: 'categories', lazy: () => import('@/features/categories/CategoriesPage').then((m) => ({ Component: m.CategoriesPage })) },
              { path: 'data', lazy: () => import('@/features/data/DataPage').then((m) => ({ Component: m.DataPage })) },
              // one route with an optional id, so a new conversation keeps the page (and its stream) when it gets an address
              { path: 'assistant/:id?', lazy: () => import('@/features/assistant/AssistantPage').then((m) => ({ Component: m.AssistantPage })) },
              { path: 'sms', lazy: () => import('@/features/ai/SmsImportPage').then((m) => ({ Component: m.SmsImportPage })) },
              { path: 'settings', lazy: () => import('@/features/settings/SettingsPage').then((m) => ({ Component: m.SettingsPage })) },
              { path: '*', Component: NotFoundPage },
            ],
          },
        ],
      },
    ],
  },
]

export function createAppRouter() {
  return createBrowserRouter(routes)
}
