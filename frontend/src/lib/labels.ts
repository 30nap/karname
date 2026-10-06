import type { AccountType, AssetClass, CommodityKind, TransactionSource, TransactionType } from './api/types'

export const ACCOUNT_TYPE_LABELS: Record<AccountType, string> = {
  CASH: 'پول نقد',
  BANK: 'حساب بانکی',
  EWALLET: 'کیف پول الکترونیک',
  CURRENCY: 'ارز',
  GOLD: 'طلا و سکه',
  CRYPTO: 'رمزارز',
  INVESTMENT: 'بورس و صندوق',
  PROPERTY: 'ملک',
  VEHICLE: 'خودرو',
  RECEIVABLE: 'طلب از دیگران',
  OTHER_ASSET: 'سایر دارایی‌ها',
  LOAN: 'وام',
  DEBT: 'بدهی به دیگران',
  CREDIT: 'خرید اقساطی / اعتباری',
}

/** Groups shown on the accounts page, in display order. */
export const ACCOUNT_GROUPS: { title: string; types: AccountType[] }[] = [
  { title: 'نقد و بانک', types: ['CASH', 'BANK', 'EWALLET'] },
  { title: 'ارز', types: ['CURRENCY'] },
  { title: 'طلا و سکه', types: ['GOLD'] },
  { title: 'رمزارز', types: ['CRYPTO'] },
  { title: 'سرمایه‌گذاری', types: ['INVESTMENT'] },
  { title: 'ملک و خودرو', types: ['PROPERTY', 'VEHICLE'] },
  { title: 'طلب‌ها', types: ['RECEIVABLE'] },
  { title: 'سایر دارایی‌ها', types: ['OTHER_ASSET'] },
  { title: 'وام‌ها و بدهی‌ها', types: ['LOAN', 'DEBT', 'CREDIT'] },
]

/** Sensible default commodity for each account type. */
export const DEFAULT_COMMODITY: Record<AccountType, string> = {
  CASH: 'IRT', BANK: 'IRT', EWALLET: 'IRT', CURRENCY: 'USD', GOLD: 'GOLD18', CRYPTO: 'USDT', INVESTMENT: 'IRT',
  PROPERTY: 'IRT', VEHICLE: 'IRT', RECEIVABLE: 'IRT', OTHER_ASSET: 'IRT', LOAN: 'IRT', DEBT: 'IRT', CREDIT: 'IRT',
}

export const COMMODITY_KIND_LABELS: Record<CommodityKind, string> = {
  TOMAN: 'تومان',
  FIAT: 'ارز',
  GOLD: 'طلا',
  COIN: 'سکه',
  CRYPTO: 'رمزارز',
  SECURITY: 'بورس و صندوق',
  PROPERTY: 'ملک',
  VEHICLE: 'خودرو',
  OTHER: 'سایر',
}

export const ASSET_CLASS_LABELS: Record<AssetClass, string> = {
  TOMAN: 'تومانی',
  FIAT: 'ارز',
  CRYPTO: 'رمزارز',
  GOLD: 'طلا و سکه',
  SECURITY: 'بورس و صندوق',
  PROPERTY: 'ملک و خودرو',
  OTHER: 'سایر',
}

/** Fixed chart slot per asset class: color follows the entity, never its rank. */
export const ASSET_CLASS_SLOT: Record<AssetClass, number> = {
  TOMAN: 1, FIAT: 2, CRYPTO: 3, GOLD: 4, SECURITY: 5, PROPERTY: 6, OTHER: 7,
}

export const TRANSACTION_TYPE_LABELS: Record<TransactionType, string> = {
  INCOME: 'درآمد',
  EXPENSE: 'هزینه',
  TRANSFER: 'انتقال',
  OPENING: 'مانده‌ی اول دوره',
  ADJUSTMENT: 'تطبیق موجودی',
}

export const SOURCE_LABELS: Record<TransactionSource, string> = {
  MANUAL: 'دستی',
  AI: 'دستیار هوشمند',
  SMS: 'پیامک بانک',
  RECURRING: 'تکراری',
  IMPORT: 'ورود فایل',
  LOAN: 'قسط وام',
  CHEQUE: 'چک',
  SYSTEM: 'سیستم',
}

export const BANKS: { code: string; name: string }[] = [
  { code: 'MELLI', name: 'ملی' },
  { code: 'MELLAT', name: 'ملت' },
  { code: 'SADERAT', name: 'صادرات' },
  { code: 'TEJARAT', name: 'تجارت' },
  { code: 'SEPAH', name: 'سپه' },
  { code: 'KESHAVARZI', name: 'کشاورزی' },
  { code: 'MASKAN', name: 'مسکن' },
  { code: 'REFAH', name: 'رفاه کارگران' },
  { code: 'POST', name: 'پست بانک' },
  { code: 'TOSEE_SADERAT', name: 'توسعه صادرات' },
  { code: 'TOSEE_TAAVON', name: 'توسعه تعاون' },
  { code: 'SANAT_MADAN', name: 'صنعت و معدن' },
  { code: 'PASARGAD', name: 'پاسارگاد' },
  { code: 'SAMAN', name: 'سامان' },
  { code: 'PARSIAN', name: 'پارسیان' },
  { code: 'EGHTESAD_NOVIN', name: 'اقتصاد نوین' },
  { code: 'KARAFARIN', name: 'کارآفرین' },
  { code: 'SINA', name: 'سینا' },
  { code: 'SHAHR', name: 'شهر' },
  { code: 'DAY', name: 'دی' },
  { code: 'SARMAYEH', name: 'سرمایه' },
  { code: 'AYANDEH', name: 'آینده' },
  { code: 'GARDESHGARI', name: 'گردشگری' },
  { code: 'IRAN_ZAMIN', name: 'ایران زمین' },
  { code: 'KHAVARMIANEH', name: 'خاورمیانه' },
  { code: 'MEHR_IRAN', name: 'قرض‌الحسنه مهر ایران' },
  { code: 'RESALAT', name: 'قرض‌الحسنه رسالت' },
  { code: 'MELAL', name: 'مؤسسه ملل' },
  { code: 'NOOR', name: 'مؤسسه نور' },
  { code: 'BLU', name: 'بلوبانک' },
  { code: 'OTHER', name: 'سایر' },
]

export const BANK_NAMES: Record<string, string> = Object.fromEntries(BANKS.map((b) => [b.code, b.name]))
