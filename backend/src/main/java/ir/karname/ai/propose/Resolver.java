package ir.karname.ai.propose;

import ir.karname.account.Account;
import ir.karname.account.AccountService;
import ir.karname.account.AccountType;
import ir.karname.ai.capture.BankNames;
import ir.karname.ai.guard.Privacy;
import ir.karname.category.Category;
import ir.karname.category.CategoryKind;
import ir.karname.category.CategoryService;
import ir.karname.commodity.Commodity;
import ir.karname.commodity.CommodityRepository;
import ir.karname.commodity.CommodityService;
import ir.karname.commodity.CommodityService.CommodityView;
import ir.karname.common.persian.PersianText;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Turns what the model passed for an account, a category or a unit (an id from a list tool, or the
 * name as the user said it) into one of the user's own records. Nothing is guessed: no match, or more
 * than one, is a problem the model gets back with the candidates, to fix or to ask the user about.
 */
@Component
public class Resolver {

    /** Everyday words for built-in units, beside their names. */
    private static final Map<String, String> UNIT_WORDS = Map.ofEntries(
            Map.entry("تومان", "IRT"), Map.entry("تومن", "IRT"), Map.entry("ریال", "IRT"),
            Map.entry("دلار", "USD"), Map.entry("یورو", "EUR"), Map.entry("پوند", "GBP"), Map.entry("درهم", "AED"),
            Map.entry("لیر", "TRY"), Map.entry("دلار کانادا", "CAD"), Map.entry("دلار استرالیا", "AUD"), Map.entry("یوان", "CNY"),
            Map.entry("طلا", "GOLD18"), Map.entry("طلای ۱۸", "GOLD18"), Map.entry("طلای 18", "GOLD18"), Map.entry("گرم طلا", "GOLD18"),
            Map.entry("طلای ۲۴", "GOLD24"), Map.entry("طلای 24", "GOLD24"), Map.entry("آبشده", "MESGHAL"), Map.entry("آب شده", "MESGHAL"),
            Map.entry("مثقال", "MESGHAL"), Map.entry("سکه", "COIN_EMAMI"), Map.entry("سکه امامی", "COIN_EMAMI"),
            Map.entry("تمام سکه", "COIN_EMAMI"), Map.entry("بهار آزادی", "COIN_BAHAR"), Map.entry("نیم سکه", "COIN_HALF"),
            Map.entry("ربع سکه", "COIN_QUARTER"), Map.entry("سکه گرمی", "COIN_GERAMI"), Map.entry("گرمی", "COIN_GERAMI"),
            Map.entry("تتر", "USDT"), Map.entry("بیت کوین", "BTC"), Map.entry("بیتکوین", "BTC"), Map.entry("اتریوم", "ETH"),
            Map.entry("تون", "TON"), Map.entry("تون کوین", "TON"));
    private static final List<String> ACCOUNT_WORDS = List.of("حساب", "کارت", "بانک", "کیف پول");

    private final AccountService accounts;
    private final CategoryService categories;
    private final CommodityService commodities;
    private final CommodityRepository commodityRepository;

    public Resolver(AccountService accounts, CategoryService categories, CommodityService commodities, CommodityRepository commodityRepository) {
        this.accounts = accounts;
        this.categories = categories;
        this.commodities = commodities;
        this.commodityRepository = commodityRepository;
    }

    /** The user's records as they are now, for one tool call. */
    @Transactional(readOnly = true)
    public Lookup lookup(long userId, LocalDate today) {
        List<Account> active = accounts.list(userId).stream().filter(a -> !a.isArchived()).toList();
        Map<Long, Commodity> units = commodityRepository.findAllById(active.stream().map(Account::getCommodityId).distinct().toList())
                .stream().collect(Collectors.toMap(Commodity::getId, Function.identity()));
        Map<Long, String> codes = new LinkedHashMap<>();
        units.forEach((id, c) -> codes.put(id, c.getCode()));
        List<Category> cats = categories.list(userId).stream().filter(c -> !c.isArchived()).toList();
        return new Lookup(userId, today, active, codes, cats, commodities.list(userId));
    }

    /** One user's accounts, categories and units, with matching by id or name. */
    public static final class Lookup {

        private final long userId;
        private final LocalDate today;
        private final List<Account> accounts;
        private final Map<Long, String> accountUnits;
        private final List<Category> categories;
        private final List<CommodityView> units;

        Lookup(long userId, LocalDate today, List<Account> accounts, Map<Long, String> accountUnits, List<Category> categories,
                List<CommodityView> units) {
            this.userId = userId;
            this.today = today;
            this.accounts = accounts;
            this.accountUnits = accountUnits;
            this.categories = categories;
            this.units = units;
        }

        public long userId() {
            return userId;
        }

        public LocalDate today() {
            return today;
        }

        public List<Account> accounts() {
            return accounts;
        }

        /** The units the user sees: built-in ones, their own, and the symbols they follow. */
        public List<CommodityView> units() {
            return units;
        }

        /** The commodity code of an account's unit. */
        public String unitOf(Account account) {
            return accountUnits.getOrDefault(account.getCommodityId(), "?");
        }

        /** A non-liability account in Toman: what pays loans and cheques. */
        public boolean isTomanAsset(Account account) {
            return !account.getType().isLiability() && "IRT".equals(unitOf(account));
        }

        /** The account everyday money comes from: the first Toman bank, wallet or cash account, as the app's forms pick it. */
        public Optional<Account> everyday() {
            return accounts.stream()
                    .filter(a -> a.getType() == AccountType.BANK || a.getType() == AccountType.EWALLET || a.getType() == AccountType.CASH)
                    .filter(this::isTomanAsset)
                    .min(Comparator.comparing((Account a) -> a.getType() == AccountType.CASH).thenComparing(Account::getSortOrder)
                            .thenComparing(Account::getId));
        }

        /**
         * The account {@code ref} names among those {@code allowed}, or null when {@code ref} is
         * empty. By id, then by name (exact, then partial), bank name or card ending.
         *
         * @param what what the field needs, in English, for the problem message
         */
        public Account account(String field, String ref, Predicate<Account> allowed, String what) {
            String text = PersianText.clean(ref);
            if (text == null) {
                return null;
            }
            List<Account> pool = accounts.stream().filter(allowed).toList();
            if (text.matches("[0-9۰-۹]{1,18}")) {
                long id = Long.parseLong(PersianText.normalizeDigits(text));
                Optional<Account> byId = accounts.stream().filter(a -> a.getId() == id).findFirst();
                if (byId.isPresent()) {
                    if (!allowed.test(byId.get())) {
                        throw problem(field, "account " + id + " «" + Privacy.mask(byId.get().getName()) + "» cannot be used here; it must be "
                                + what, pool);
                    }
                    return byId.get();
                }
            }
            String q = strip(PersianText.normalizeForSearch(text));
            List<Function<Account, Boolean>> steps = List.of(
                    a -> strip(PersianText.normalizeForSearch(a.getName())).equals(q)
                            || strip(PersianText.normalizeForSearch(Privacy.mask(a.getName()))).equals(q),
                    a -> contains(strip(PersianText.normalizeForSearch(a.getName())), q),
                    a -> a.getBank() != null && BankNames.NAMES.containsKey(a.getBank())
                            && q.contains(strip(PersianText.normalizeForSearch(BankNames.NAMES.get(a.getBank())))),
                    a -> q.matches(".*[0-9]{4}.*") && a.getIdentifierHints().stream()
                            .map(h -> PersianText.normalizeDigits(h).replaceAll("\\D", ""))
                            .anyMatch(h -> h.length() >= 4 && q.contains(h.substring(h.length() - 4))));
            for (Function<Account, Boolean> step : steps) {
                List<Account> found = pool.stream().filter(step::apply).toList();
                if (found.size() == 1) {
                    return found.getFirst();
                }
                if (found.size() > 1) {
                    throw problem(field, "«" + text + "» matches several accounts; pass the id of the one the user means, or ask them",
                            found);
                }
            }
            throw problem(field, "no account matches «" + text + "»" + (pool.isEmpty() ? "; the user has no account that is " + what
                    + ", so propose one with propose_account first or ask the user" : "; it must be " + what), pool);
        }

        /** Several accounts by id or name; an empty list for none. */
        public List<Account> accounts(String field, List<String> refs, Predicate<Account> allowed, String what) {
            List<Account> found = new ArrayList<>();
            for (String ref : refs) {
                Account a = account(field, ref, allowed, what);
                if (a != null && !found.contains(a)) {
                    found.add(a);
                }
            }
            return found;
        }

        /** The category of {@code kind} that {@code ref} names, or null when it is empty. */
        public Category category(String field, String ref, CategoryKind kind) {
            String text = PersianText.clean(ref);
            if (text == null) {
                return null;
            }
            List<Category> pool = categories.stream().filter(c -> c.getKind() == kind).toList();
            if (text.matches("[0-9۰-۹]{1,18}")) {
                long id = Long.parseLong(PersianText.normalizeDigits(text));
                Optional<Category> byId = pool.stream().filter(c -> c.getId() == id).findFirst();
                if (byId.isPresent()) {
                    return byId.get();
                }
            }
            Map<Long, String> names = categories.stream().collect(Collectors.toMap(Category::getId, Category::getName));
            String q = PersianText.normalizeForSearch(text.replace('›', '/').replace('>', '/'));
            List<Function<Category, Boolean>> steps = List.of(
                    c -> PersianText.normalizeForSearch(c.getName()).equals(q),
                    c -> c.getParentId() != null && q.contains("/")
                            && PersianText.normalizeForSearch(names.get(c.getParentId()) + "/" + c.getName()).replace(" / ", "/")
                            .equals(q.replace(" / ", "/")),
                    c -> contains(PersianText.normalizeForSearch(c.getName()), q));
            for (Function<Category, Boolean> step : steps) {
                List<Category> found = pool.stream().filter(step::apply).toList();
                if (found.size() == 1) {
                    return found.getFirst();
                }
                if (found.size() > 1) {
                    // a parent and its own child both match: the parent is what was named
                    List<Category> parents = found.stream().filter(c -> c.getParentId() == null).toList();
                    if (parents.size() == 1 && found.stream().allMatch(c -> c.getParentId() == null
                            || c.getParentId().equals(parents.getFirst().getId()))) {
                        return parents.getFirst();
                    }
                    throw new IllegalArgumentException(field + ": «" + text + "» matches several " + kind + " categories: "
                            + categories(found, names) + "; pass the id of the one meant, or ask the user");
                }
            }
            throw new IllegalArgumentException(field + ": no " + kind + " category matches «" + text + "». The user's " + kind
                    + " categories: " + categories(pool, names) + ". Use one of them, or propose a new one with propose_category.");
        }

        /** The unit {@code ref} names (a code such as USD, or a name such as «یورو» or «سکه امامی»), or null when empty. */
        public CommodityView unit(String field, String ref) {
            String text = PersianText.clean(ref);
            if (text == null) {
                return null;
            }
            Optional<CommodityView> byCode = units.stream().filter(u -> u.code().equalsIgnoreCase(text.strip())).findFirst();
            if (byCode.isPresent()) {
                return byCode.get();
            }
            String q = PersianText.normalizeForSearch(text);
            String word = UNIT_WORDS.entrySet().stream().filter(e -> PersianText.normalizeForSearch(e.getKey()).equals(q))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            if (word != null) {
                Optional<CommodityView> found = units.stream().filter(u -> u.code().equals(word)).findFirst();
                if (found.isPresent()) {
                    return found.get();
                }
            }
            List<Function<CommodityView, Boolean>> steps = List.of(
                    u -> PersianText.normalizeForSearch(u.nameFa()).equals(q)
                            || u.stock() != null && u.stock().symbol() != null && PersianText.normalizeForSearch(u.stock().symbol()).equals(q),
                    u -> contains(PersianText.normalizeForSearch(u.nameFa()), q));
            for (Function<CommodityView, Boolean> step : steps) {
                List<CommodityView> found = units.stream().filter(step::apply).toList();
                if (found.size() == 1) {
                    return found.getFirst();
                }
                if (found.size() > 1) {
                    throw new IllegalArgumentException(field + ": «" + text + "» matches several units: " + units(found)
                            + "; pass the code of the one meant");
                }
            }
            throw new IllegalArgumentException(field + ": no unit matches «" + text + "». Units available: " + units(units)
                    + ". For a share or fund use propose_security; for anything else the user can add a unit on the assets page.");
        }

        private static IllegalArgumentException problem(String field, String message, List<Account> candidates) {
            String list = candidates.stream().limit(15).map(a -> a.getId() + " «" + Privacy.mask(a.getName()) + "»")
                    .collect(Collectors.joining(", "));
            return new IllegalArgumentException(field + ": " + message + (list.isEmpty() ? "." : ". Candidates: " + list + "."));
        }

        private static String categories(List<Category> list, Map<Long, String> names) {
            return list.stream().limit(40).map(c -> c.getId() + " «" + (c.getParentId() == null ? "" : names.get(c.getParentId()) + " › ")
                    + c.getName() + "»").collect(Collectors.joining(", "));
        }

        private static String units(List<CommodityView> list) {
            return list.stream().limit(40).map(u -> u.code() + " «" + u.nameFa() + "»").collect(Collectors.joining(", "));
        }

        /** Without the generic words people put before an account's name («حساب ملت» is «ملت»). */
        private static String strip(String normalized) {
            String s = normalized;
            boolean changed = true;
            while (changed) {
                changed = false;
                for (String word : ACCOUNT_WORDS) {
                    String w = PersianText.normalizeForSearch(word) + " ";
                    if (s.startsWith(w) && s.length() > w.length()) {
                        s = s.substring(w.length());
                        changed = true;
                    }
                }
            }
            return s;
        }

        private static boolean contains(String name, String q) {
            return q.length() >= 2 && name.length() >= 2 && (name.contains(q) || q.contains(name));
        }
    }
}
