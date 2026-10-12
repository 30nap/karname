# Prices

Everything Karname shows in Toman, dollars or grams of gold is valued at prices. Toman is the base
unit; every other unit (dollar, euro, gram of 18k gold, Emami coin, Tether, a share you defined)
has prices in Toman over time.

## Where prices come from

| Kind | Who sees it | How |
|---|---|---|
| Manual | You (or everyone, when the administrator marks it so) | **دارایی‌ها و قیمت‌ها** → a unit → «ثبت قیمت»; any date, for example the day of a purchase |
| Implied by a purchase | You | Buying 300 dollars for 25,350,000 Toman records 84,500 Toman per dollar on that day |
| Automatic sources | Everyone | Fetched by the server on a schedule, from several services at once (below) |

**Which price counts:** for a given moment, the most recent price before it, whichever kind it
is. A manual price therefore overrides automatic ones until the next fetch is newer; to keep a
unit on your own price, do not map it in an automatic source. Each price shows its source and age,
and a unit is flagged as stale when its latest price is older than a few days (two days for
currencies, gold, coins and crypto, a week for shares, six months for property and vehicles).

## Automatic sources

Administrators manage them in **تنظیمات → مدیریت → منابع قیمت خودکار**. «منبع جدید» offers ready-made
sources (below) or any JSON API configured by hand. Each source has an interval (5 minutes to a
day), an on/off switch, «آزمایش دریافت» to try a configuration without recording anything (with the
current price beside each figure, and a warning when they differ by more than 20%), and a button to
fetch at once. The last success and the last error are shown, with the reason the service gave
(for example `سرویس با کد HTTP ۴۰۰ پاسخ داد: Invalid srcCurrency`); a failure never touches the
prices already recorded.

### Several sources, one price

A unit can be published by several sources, for example Tether by Nobitex, Wallex and Bitpin, or
the dollar by a gold-and-currency service and, as a stand-in, by Tether. The sources are kept in
order of preference (the arrows beside each source):

- Every source keeps its latest quote for every unit it publishes.
- The recorded price is the quote of the **most preferred source whose quote is fresh**: fetched
  within twice the source's interval plus five minutes. If that source fails, is slow or is
  switched off, the next one takes over on its next fetch; when it comes back, it takes over again.
- **Cross-checks:** with three or more fresh quotes, one more than 20% from their median is set
  aside (typically Rial read as Toman, or a price per mesghal read as per gram) and the source's
  row says so. With two quotes that disagree by more than 20%, the one closer to the last recorded
  price wins, and a warning is shown. A change of more than 50% from the last recorded price is
  flagged but still recorded.
- «قیمت هر واحد از کجا می‌آید», under the sources, shows every unit with the quote in use and the
  others: in use, backup, set aside, stale or switched off.
- A source switched off but fetched by hand («دریافت حالا») takes part that once.

To keep the history small, an unchanged price is recorded again only every six hours, and after
30 days fetched prices are thinned to one per day.

### Ready-made sources

| Source | Publishes | Key | Notes |
|---|---|---|---|
| Nobitex | Tether, Bitcoin, Ether, Toncoin (Gram) in Rial | No | Built in and switched off at first |
| Wallex | Tether, Bitcoin, Ether, Toncoin (Gram) in Toman | No | One answer with every market (about 0.5 MB) |
| Bitpin | Tether, Bitcoin, Ether in Toman | No | If the address stops answering, try `api.bitpin.org` |

Tether in Toman is a fair stand-in for the open-market dollar; map `USD` to it in a crypto source
if no currency source is set up, and put that source after a real currency source.

#### Nobitex

Reads `https://apiv2.nobitex.ir/market/stats?srcCurrency=usdt,btc,…&dstCurrency=rls` (the host
moved from `api.nobitex.ir`, which no longer resolves, in mid-2025). Each mapping is a Nobitex
symbol (`usdt`, `btc`, `1m_pepe`…). Nobitex refuses the whole request with HTTP 400 when one
symbol is unknown; Karname then asks for the symbols one by one, so the others still arrive and
the unknown one is named. Toncoin was renamed Gram in June 2026 and Nobitex closed its TON
markets: `ton` is asked as `gram`, and existing installations were updated to `gram`. A market
Nobitex marks as closed is not used: its figures are frozen.

### Any JSON API

For gold, coins and currencies from any service that answers with JSON:

- **Address** and optional **headers** (stored encrypted and never shown again; changing the
  address requires entering them again).
- **API key:** if the service wants its key in the address or in a header, write `{key}` there
  (`https://api.example.com/latest?api_key={key}`) and enter the key in its own field. It is stored
  encrypted, filled in only when the request is made, never shown again, removed from any error
  message, and not sent to a changed address until entered again.
- **Unit of the numbers**: Rial or Toman, for the whole source or per mapping. Rial is divided by
  ten in code.
- **Mappings**: for each unit, a [JSON Pointer](https://www.rfc-editor.org/rfc/rfc6901) to the
  number in the response, and an optional multiplier. When the answer is a list of items, pick
  an item by one of its fields with `[field=value]`: `/gold/[symbol=IR_GOLD_18K]/price`
  (ignoring case and the order of the list).

Example: the service answers

```json
{ "data": { "usd": { "price": "1025000" }, "gold18": { "price": "89500000" } } }
```

in Rial. Map `USD` to `/data/usd/price` and `GOLD18` to `/data/gold18/price` with the unit set to
Rial: the dollar is recorded at 102,500 Toman and a gram of 18k gold at 8,950,000. Numbers may be
JSON numbers or text, with Persian digits and thousands separators. If a service quotes a coin in
thousands of Toman, use the multiplier `1000`.

When a service changes its format, the test shows which path no longer matches; fix the path,
no code change needed.

Addresses may be on the internet or the local network, but not link-local (cloud metadata), and
redirects are not followed: a moved service is reported with its new address, to be entered by hand.

## When a source fails

The error under the source says what the service answered. Common cases:

| Error | Meaning |
|---|---|
| `کد HTTP ۴۰۰` with a reason | The request was refused: a symbol or parameter the service does not know |
| `کد HTTP ۴۰۱` / `۴۰۳` | Missing or wrong key, or the service blocks the server's address |
| `کد HTTP ۴۲۹` | Too many requests: lengthen the interval |
| `نام سرویس پیدا نشد (DNS)` | The address is wrong, or the server has no internet access |
| `هدایت کرد` | The service moved; the new address is shown |

Some services answer only from inside Iran, and some only from outside; if a source works on one
server and not another, that is usually why.
