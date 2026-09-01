# DonutOrders

**A player-driven order board for Minecraft servers.** Players post buy-orders for the
items they want; anyone else can fill them and is paid per item delivered.

![Version](https://img.shields.io/badge/version-3.1-blue)
![Minecraft](https://img.shields.io/badge/minecraft-1.21%2B-brightgreen)
![Platform](https://img.shields.io/badge/platform-Paper%20%7C%20Spigot%20%7C%20Folia-orange)
![Java](https://img.shields.io/badge/java-17%2B-red)
![License](https://img.shields.io/badge/license-PolyForm%20Noncommercial-lightgrey)

Chest shops only work when a seller happens to be online with what you need.
An order board flips it around: you post what you want and the price you will pay,
the money is escrowed immediately, and anyone on the server can earn it by delivering.

---

## How it works

1. A player runs `/order`, picks an item from a visual menu, sets an amount and a
   price per item. The total is withdrawn up front and held in escrow.
2. The order goes on the board for everyone to see.
3. Anyone else delivers matching items. **Partial deliveries count** and are paid
   proportionally — a 9,000-item order can be filled by fifty different people.
4. The owner collects the delivered goods whenever they like, or cancels and gets
   the unfilled remainder refunded.

Because the money is taken at creation, a deliverer is always paid. There is no
"the buyer went offline" failure mode.

---

## Quick start

```
1. Install Vault and any economy plugin it supports.
2. Drop DonutOrders.jar into plugins/.
3. Start the server.
4. /order
```

That is the whole setup. Everything below is optional tuning.

---

## Features

### For players

| | |
|---|---|
| **Visual item picker** | Browse and click. No typing material names. |
| **Enchantments and potions** | Order items that must carry specific enchantments or potion effects. |
| **Partial delivery** | Deliver ten of a nine-thousand order and get paid for ten. |
| **Four sort modes** | Most paid · most delivered · recently listed · most money per item. |
| **Eight category filters** | Blocks, tools, food, combat, potions, books, ingredients, utilities. |
| **Sign-based search** | Search opens a sign editor. No chat syntax to remember. |
| **Your orders** | One click shows everything you posted and how long each has left. |

### For server owners

| | |
|---|---|
| **Every menu is a file** | 14 YAML layouts. Rows, slots, materials, sounds, filler, per-button toggles. |
| **Templated item lore** | Reorder lines, add or remove them, use hex colours, progress bars and thousands separators — without touching code. |
| **13 languages** | Selectable per player. English, Turkish, German, Spanish, French, Italian, Portuguese, Russian, Polish, Dutch, Czech, Chinese, Japanese. |
| **Tax system** | Separate rates for order creation, delivery and selling, with per-rank rates and exemptions. |
| **Order levels** | Players unlock higher limits as they trade, with anti-abuse throttling so repeated trades with the same partner stop earning progress. |
| **Custom items** | ItemsAdder, Oraxen, Nexo, or plain custom-model-data. |
| **Admin panel** | In-game stats, cleanup, and live feature toggles. |
| **Folia** | Region-aware scheduling throughout, not a compatibility shim. |

### Storage

Four backends, set by `storage.type`:

| Type | Notes |
|---|---|
| `MEMORY` | Flat `data.yml`. The default — works with no setup at all. |
| `SQLITE` | Local database file, still no external server. |
| `MYSQL` | MySQL, pooled with HikariCP. |
| `MARIADB` | MariaDB. Recommended for production. |

Several servers can share one database and one order board. Fills are resolved by a
conditional atomic `UPDATE`, so two players on two servers cannot be paid for the
same slot.

> **If more than one server shares a database, set `network.enabled: true` on every
> one of them.** The plugin logs a warning on startup when it finds MySQL storage
> with networking left off, because that combination is silently unsafe.

---

## Commands

| Command | Aliases | Purpose |
|---|---|---|
| `/order [item]` | `orders`, `siparis` | Open the order board |
| `/donutordersadmin [menu\|reload\|removeorder]` | `donutorders`, `ordersadmin`, `siparisadmin` | Admin panel |
| `/orderlang [lang\|auto]` | `orderdil`, `ordersdil` | Change your language |

## Permissions

| Node | Default | Grants |
|---|---|---|
| `orders.admin` | op | Admin panel, remove any order |
| `orders.unlimited` | — | Unlimited active orders |
| `orders.bypass.cooldown` | op | Exempt from spam/cooldown protection |
| `orders.bypass.expire` | — | Orders never expire |
| `orders.tax.exempt` | — | Fully exempt from tax |
| `orders.rank.vip` … `.legend` | — | Rank nodes for `tax.rank-rates` / `rank-discounts` |
| `orders.limit.vip` / `.mvp` | — | Nodes for `orders.rank-limits` |

The rank, limit, tax-exempt and bypass nodes are all **configurable strings** — point
them at whatever permissions your server already issues instead of adding new ones.

---

## Configuration

| File | Controls |
|---|---|
| `config.yml` | Order limits, expiry, tax, sounds, protection, storage, cross-server |
| `menus/*.yml` | Layout of each of the 14 menus — slots, materials, sounds, filler |
| `lang/*.yml` | Every string, and the item-lore templates |
| `levels.yml` | Order level tiers and their rewards |

**Upgrading is safe.** New settings are merged into your existing files without
overwriting anything you changed, and your comments are preserved. Deleting a menu
button is treated as a decision — it will not come back on the next update.

### Order broadcasts

Off by default. Turn it on to announce new orders server-wide, with a minimum order
total so small orders do not flood chat:

```yaml
orders:
  broadcast:
    enabled: true
    min-total: 10000
```

---

## Building

```
mvn clean package
```

Output lands in `target/`. Requires JDK 17 or newer.

---

## License

[PolyForm Noncommercial 1.0.0](LICENSE) — free for personal, community and other
noncommercial use.

**Selling this software, bundling it into a paid product, or offering it as part of
a paid service is not permitted.**
