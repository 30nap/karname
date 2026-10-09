# Prices

Everything Karname shows in Toman, dollars or grams of gold is valued at prices. Toman is the base
unit; every other unit (dollar, euro, gram of 18k gold, Emami coin, Tether, a share you defined)
has prices in Toman over time.

## Where prices come from

| Kind | Who sees it | How |
|---|---|---|
| Manual | You (or everyone, when the administrator marks it so) | **دارایی‌ها و قیمت‌ها** → a unit → «ثبت قیمت»; any date, for example the day of a purchase |
| Implied by a purchase | You | Buying 300 dollars for 25,350,000 Toman records 84,500 Toman per dollar on that day |
| Automatic source | Everyone | Fetched by the server on a schedule (below) |

**Which price counts:** for a given moment, the most recent price before it, whichever kind it
is. A manual price therefore overrides automatic ones until the next fetch is newer; to keep a
unit on your own price, do not map it in an automatic source. Each price shows its source and age,
and a unit is flagged as stale when its latest price is older than a few days (two days for
currencies, gold, coins and crypto, a week for shares, six months for property and vehicles).

## Automatic sources

Administrators manage them in **تنظیمات → مدیریت → منابع قیمت خودکار**. Each source has an interval
(5 minutes to a day), an on/off switch, «آزمایش دریافت» to try a configuration without recording
anything, and a refresh button to fetch at once. The last success and the last error are shown; a
failure never touches the prices already recorded.

To keep the history small, an unchanged price is recorded again only every six hours, and after
30 days fetched prices are thinned to one per day.

### Nobitex

Built in and switched off at first: it reads Tether, Bitcoin, Ether and Toncoin in Rial from
`apiv2.nobitex.ir` without a key and converts them to Toman. Tether in Toman is a fair stand-in for
the open-market dollar. Each mapping is a Nobitex symbol (`usdt`, `btc`, …).

### Any JSON API

For gold, coins and currencies from any service that answers with JSON:

- **Address** and optional **headers** (an API key, for example; stored encrypted and never shown
  again; changing the address requires entering them again).
- **Unit of the numbers**: Rial or Toman. Rial is divided by ten in code.
- **Mappings**: for each unit, a [JSON Pointer](https://www.rfc-editor.org/rfc/rfc6901) to the
  number in the response, and an optional multiplier.

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
redirects are not followed.
