import {
  Baby, BadgePercent, BookOpen, Briefcase, Building, Bus, Car, Coffee, Coins, Droplets, Dumbbell, Ellipsis, Film, Flame, Fuel, Gamepad2, Gift, GraduationCap, HandCoins, HandHeart, HeartPulse, House, Landmark, Laptop, Music, PartyPopper, PawPrint, Percent, Phone, Pill, Plane, Receipt, Scale, Shirt, ShoppingBag, ShoppingCart, Smartphone, Sofa, Sparkles, Stethoscope, Tag, Train, TrendingUp, Utensils, Wallet, Wifi, Wrench, Zap,
  type LucideIcon,
} from 'lucide-react'

/** Icons selectable for categories (lucide names). */
export const CATEGORY_ICONS: Record<string, LucideIcon> = {
  'shopping-cart': ShoppingCart, utensils: Utensils, car: Car, house: House, receipt: Receipt, 'heart-pulse': HeartPulse,
  'graduation-cap': GraduationCap, shirt: Shirt, plane: Plane, wifi: Wifi, smartphone: Smartphone, sparkles: Sparkles, gift: Gift,
  'hand-heart': HandHeart, landmark: Landmark, percent: Percent, scale: Scale, ellipsis: Ellipsis, briefcase: Briefcase,
  'party-popper': PartyPopper, laptop: Laptop, 'trending-up': TrendingUp, building: Building, 'badge-percent': BadgePercent,
  tag: Tag, 'paw-print': PawPrint, 'shopping-bag': ShoppingBag, 'gamepad-2': Gamepad2, baby: Baby, dumbbell: Dumbbell,
  'book-open': BookOpen, coffee: Coffee, fuel: Fuel, bus: Bus, wrench: Wrench, sofa: Sofa, zap: Zap, droplets: Droplets,
  flame: Flame, phone: Phone, pill: Pill, stethoscope: Stethoscope, film: Film, music: Music, train: Train, coins: Coins,
  'hand-coins': HandCoins, wallet: Wallet,
}

/** Persian names of the category icons, for screen readers and tooltips. */
export const CATEGORY_ICON_LABELS: Record<string, string> = {
  'shopping-cart': 'سبد خرید', utensils: 'غذا', car: 'خودرو', house: 'خانه', receipt: 'قبض', 'heart-pulse': 'سلامت',
  'graduation-cap': 'آموزش', shirt: 'پوشاک', plane: 'سفر', wifi: 'اینترنت', smartphone: 'موبایل', sparkles: 'زیبایی',
  gift: 'هدیه', 'hand-heart': 'نیکوکاری', landmark: 'بانک', percent: 'درصد', scale: 'ترازو', ellipsis: 'سایر',
  briefcase: 'کار', 'party-popper': 'جشن', laptop: 'لپ‌تاپ', 'trending-up': 'رشد', building: 'ساختمان',
  'badge-percent': 'تخفیف', tag: 'برچسب', 'paw-print': 'حیوان خانگی', 'shopping-bag': 'خرید', 'gamepad-2': 'سرگرمی',
  baby: 'کودک', dumbbell: 'ورزش', 'book-open': 'کتاب', coffee: 'کافه', fuel: 'سوخت', bus: 'حمل‌ونقل عمومی',
  wrench: 'تعمیرات', sofa: 'لوازم خانه', zap: 'برق', droplets: 'آب', flame: 'گاز', phone: 'تلفن', pill: 'دارو',
  stethoscope: 'پزشک', film: 'فیلم', music: 'موسیقی', train: 'قطار', coins: 'سکه', 'hand-coins': 'وام و قرض', wallet: 'کیف پول',
}
