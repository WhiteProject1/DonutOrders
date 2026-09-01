# Changelog

## 3.2

Added fully configurable order lore — the description under every item on the board is now a template in `lang/*.yml` instead of nine lines assembled in Java. Reorder them, delete them, add your own, with hex colours and blank lines wherever you want them.

Added twelve lore placeholders: `%item%`, `%owner%`, `%price%`, `%total%`, `%paid%`, `%filled%`, `%needed%`, `%remaining%`, `%percent%`, `%bar%`, `%time%` and `%enchants%`

Added a styleable progress bar under `text.progress-bar` — length, the character used for filled and empty segments, and a colour for each

Added full number formatting alongside the existing abbreviated form, so prices can read `45.000.000` instead of `45M`, with a configurable thousands separator under `text.number-format`

Added a configurable percent format, so the same template renders `%0` in Turkish and `0%` in English without a second template

Added the same template treatment to every button in the admin panel

Added `orders.broadcast` — announces a new order to the whole server, with the item, amount and price. Off by default, and gated behind a `min-total` threshold so small orders do not flood chat

Added `orders.bypass.expire` — orders posted by a holder of this permission never expire. Like the existing cooldown bypass, the permission string itself is configurable, so you can point it at a rank you already issue

Added a `time.never` line, so an order that cannot expire shows "Never" rather than a countdown measured in millions of days

Added the admin panel's feature-toggle list to `config.yml`, so toggles can be added or removed without recompiling

Fixed a cancellation racing an in-flight delivery and paying for the same items twice — on Folia the owner's cancel and the deliverer's completion run on different region threads, and the order's presence check was a separate statement from the fill that followed it, so the owner could be refunded for units the deliverer was simultaneously being paid for. Cancelling and filling are now mutually exclusive on the order itself, and every refund is calculated from the value captured at the instant the order closed rather than read afterwards.

Fixed delivered items being silently erased when a delivery landed while the owner had the collect menu open — collecting read a copy of the order's storage, modified the copy, and wrote it back, discarding anything that arrived in between

Fixed the admin panel listing raw configuration keys as feature names, so operators saw `enchanted-books` where a translated name belonged

Fixed the progress bar emitting a colour code for the filled section even when nothing had been delivered

Fixed a raw NUL byte written directly into a source file as a string separator, which made the file read as binary to tooling and was liable to be mangled by editors and encoding conversions

Fixed an integer overflow that non-expiring orders would otherwise have hit in the completed-order retention sweep, where adding the retention window to an effectively infinite expiry wrapped to a date in the past and would have deleted the order immediately

Improved the startup log to warn when MySQL storage is configured with `network.enabled` left off — that combination is silently unsafe once more than one server shares the database, and nothing previously said so

## 3.1

Professional message pass, spam protection, order-level abuse protection, and optimisation.

## 3.0

Cross-server support over a shared database, custom item support for ItemsAdder, Oraxen and Nexo, an administrator GUI panel, a tax service, the order level system, 13 bundled languages, and a per-menu sound engine.
