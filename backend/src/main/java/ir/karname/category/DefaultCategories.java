package ir.karname.category;

import java.util.List;

/** The Persian category tree every new user starts with. Icons are lucide icon names. */
final class DefaultCategories {

    record Seed(String name, String icon, String systemKey, List<Seed> children) {

        static Seed of(String name, String icon, Seed... children) {
            return new Seed(name, icon, null, List.of(children));
        }

        static Seed key(String name, String icon, String systemKey, Seed... children) {
            return new Seed(name, icon, systemKey, List.of(children));
        }

        static Seed leaf(String name) {
            return new Seed(name, null, null, List.of());
        }
    }

    static final List<Seed> EXPENSE = List.of(
            Seed.of("خوراک و خواربار", "shopping-cart",
                    Seed.leaf("سوپرمارکت"), Seed.leaf("نان و لبنیات"), Seed.leaf("میوه و تره‌بار"), Seed.leaf("گوشت و مرغ")),
            Seed.of("رستوران و کافه", "utensils",
                    Seed.leaf("رستوران"), Seed.leaf("کافه"), Seed.leaf("غذای بیرون‌بر")),
            Seed.of("حمل‌ونقل", "car",
                    Seed.leaf("تاکسی اینترنتی"), Seed.leaf("بنزین"), Seed.leaf("مترو و اتوبوس"), Seed.leaf("تعمیر و سرویس خودرو"),
                    Seed.leaf("بیمه و عوارض خودرو"), Seed.leaf("پارکینگ")),
            Seed.of("مسکن", "house",
                    Seed.leaf("اجاره"), Seed.leaf("شارژ ساختمان"), Seed.leaf("تعمیرات منزل"), Seed.leaf("لوازم خانه")),
            Seed.of("قبوض", "receipt",
                    Seed.leaf("برق"), Seed.leaf("آب"), Seed.leaf("گاز"), Seed.leaf("اینترنت"), Seed.leaf("تلفن همراه")),
            Seed.of("سلامت", "heart-pulse",
                    Seed.leaf("دارو"), Seed.leaf("پزشک و درمان"), Seed.leaf("دندان‌پزشکی"), Seed.leaf("بیمه درمان")),
            Seed.of("آموزش", "graduation-cap",
                    Seed.leaf("شهریه"), Seed.leaf("کتاب"), Seed.leaf("دوره آموزشی")),
            Seed.of("پوشاک", "shirt"),
            Seed.of("تفریح و سفر", "plane",
                    Seed.leaf("سفر"), Seed.leaf("سینما و تئاتر"), Seed.leaf("ورزش")),
            Seed.of("اشتراک و سرویس آنلاین", "wifi",
                    Seed.leaf("VPN و سرویس خارجی"), Seed.leaf("اشتراک فیلم و موسیقی"), Seed.leaf("نرم‌افزار")),
            Seed.of("لوازم دیجیتال", "smartphone"),
            Seed.of("مراقبت شخصی", "sparkles"),
            Seed.of("هدیه و مناسبت", "gift"),
            Seed.of("خیریه", "hand-heart"),
            Seed.key("کارمزد بانکی", "landmark", "bank_fees"),
            Seed.key("سود و کارمزد وام", "percent", "loan_interest"),
            Seed.of("مالیات و عوارض", "scale"),
            Seed.key("سایر هزینه‌ها", "ellipsis", "other_expense"));

    static final List<Seed> INCOME = List.of(
            Seed.key("حقوق و دستمزد", "briefcase", "salary"),
            Seed.of("پاداش و عیدی", "party-popper"),
            Seed.of("پروژه و درآمد آزاد", "laptop"),
            Seed.key("سود سپرده و سرمایه‌گذاری", "trending-up", "interest_income"),
            Seed.of("اجاره‌بها", "building"),
            Seed.of("یارانه", "badge-percent"),
            Seed.of("فروش وسایل", "tag"),
            Seed.of("هدیه دریافتی", "gift"),
            Seed.key("سایر درآمدها", "ellipsis", "other_income"));

    private DefaultCategories() {
    }
}
