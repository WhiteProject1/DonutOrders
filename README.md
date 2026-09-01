# DonutOrders

A player-driven order board for Minecraft servers. Players post buy-orders for the
items they want; anyone else can fill them and get paid per item delivered.

Instead of hunting through chest shops for a seller who happens to have what you need,
you post what you want and let the server come to you. The money is escrowed when the
order is created, so a deliverer is always paid.

**Version 3.1** · Paper / Spigot / **Folia** · Minecraft 1.21+ · requires Vault

---

## How it works

1. A player opens `/orders`, picks an item, sets an amount and a price per item.
   The total is withdrawn up front and held.
2. The order appears on the board for everyone.
3. Any other player delivers matching items — **partial deliveries count** and are paid
   proportionally, so a 9,000-item order can be filled by fifty people.
4. The owner collects the delivered goods from their own order at any time, or cancels
   and gets the unfilled remainder refunded.

## Features

**The board**

- Visual item picker — no typing material names
- Enchantment and potion selectors for orders that demand specific properties
- Four sort modes: most paid, most delivered, recently listed, most money per item
- Eight category filters: blocks, tools, food, combat, potions, books, ingredients, utilities
- Search by item, typed on a **sign** rather than in chat

**Economy**

- Configurable tax on order creation, delivery and selling, each with its own rate
- Order levels: players unlock higher limits as they trade, with anti-abuse throttling
  so repeated trades with the same partner stop earning progress
- Vault integration for any supported economy plugin

**Server owners**

- Every menu is a YAML file: rows, slots, materials, sounds, filler, per-button toggles
- Item lore is a **template** — reorder lines, add or remove them, use hex colours,
  progress bars and thousands separators, all without touching code
- 13 built-in languages, selectable per player
- Custom item support: ItemsAdder, Oraxen, Nexo, or plain custom-model-data
- In-game admin panel for stats, cleanup, and toggling features live
- Folia-aware scheduling throughout

**Storage**

- SQLite by default, MySQL/MariaDB optional
- Cross-server: several servers can share one database and one order board.
  Fills are resolved by a conditional atomic `UPDATE`, so two players on two servers
  cannot be paid for the same slot.

  > If more than one server shares a database, set `network.enabled: true` on every
  > one of them. The plugin warns on startup when it detects MySQL storage with
  > networking left off.

## Commands

| Command | Aliases | Purpose |
|---|---|---|
| `/order [item]` | `orders`, `siparis` | Open the order board |
| `/donutordersadmin [menu\|reload\|removeorder]` | `donutorders`, `ordersadmin`, `siparisadmin` | Admin panel |
| `/orderlang [lang\|auto]` | `orderdil`, `ordersdil` | Change your language |

## Permissions

| Node | Default | Grants |
|---|---|---|
| `orders.admin` | op | Admin panel and removing any order |
| `orders.unlimited` | — | Unlimited active orders |
| `orders.bypass.cooldown` | op | Exempt from spam/cooldown protection |
| `orders.tax.exempt` | — | Fully exempt from tax |
| `orders.rank.vip` … `.legend` | — | Example rank nodes for `tax.rank-rates` / `rank-discounts` |
| `orders.limit.vip` / `.mvp` | — | Example nodes for `orders.rank-limits` |

The rank and limit nodes are examples — the permission strings they map to are
configurable, so you can wire them to whatever ranks your server already has.

## Installation

1. Install [Vault](https://www.spigotmc.org/resources/vault.34315/) and an economy plugin.
2. Drop the jar into `plugins/`.
3. Start the server, then edit `plugins/DonutOrders/config.yml` and the files under
   `menus/` and `lang/`.

Upgrading is safe: new settings are merged into your existing configuration without
overwriting anything you changed, and your comments are preserved.

## Building

```
mvn clean package
```

The jar lands in `target/`. Requires JDK 17 or newer.

## License

[PolyForm Noncommercial 1.0.0](LICENSE) — free for personal, community and other
noncommercial use. **Selling this software, or using it as part of a paid product or
service, is not permitted.**
