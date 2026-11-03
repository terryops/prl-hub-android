# Pearl Hub Android — web ↔ native contract

The Android app is a native shell around the bundled web UI. This file is the contract
between the Kotlin side (`app/` in this repository) and the web side: `web/src/android*.ts` in
the prl-hub-web repository and the wallet's `src/android/` in prl-hub-wallet. Change both
sides together.

## Origin and loading

- The UI is served from the APK at `https://pearlhub.net/`. The shell intercepts every
  `GET https://pearlhub.net/<path>` whose path maps to a file under
  `app/src/main/assets/www/` (`/` and any path ending in `/` map to `index.html` in that
  folder) and answers it from the APK with the right MIME type and `Cache-Control: no-cache`.
  Anything else on that host — the `/v1/*` API above all — goes to the network untouched, so
  the API, cookies and account sessions are same-origin exactly as on the website.
- HTML files served from the APK carry the `Content-Security-Policy` header read from
  `www/csp.txt` (one line, written by the web build). No CSP header if the file is missing.
- Start URL: `https://pearlhub.net/?client=android&store=<play|direct>` — `store` is the
  product flavor. A notification tap adds a hash route, e.g. `#workers`.
- Main-frame navigation to anything that is not `https://pearlhub.net` is cancelled and opened
  in the system browser. `window.open` / `target=_blank` (any URL, including pearlhub.net
  pages such as `/privacy.html`) also opens in the system browser.
- Blockbook has no CORS headers. For `GET` requests to `https://blockbook.pearlresearch.ai/api/*`
  and `https://blockbook.testnet.pearlresearch.ai/api/*` the shell performs the request itself
  and returns the upstream status, body and content type plus
  `Access-Control-Allow-Origin: https://pearlhub.net`. `POST` bodies are not visible to
  `shouldInterceptRequest`, so posts go through `net.post` below.
- The wallet's NOCK side reads two Nockchain nodes, as the iOS app does. `https://rpc.nockbox.org`
  sends CORS headers, so the page fetches it directly. The official `nockchain-wallet` CLI's default
  node, `http://216.158.95.10:5556`, is plain HTTP on a bare IP with no CORS headers, so its calls
  go through `nock.rpc` below. `res/xml/network_security_config.xml` allows cleartext to that one
  address and nothing else — the counterpart of the iOS app's ATS exception for it.

## Message channel

`WebViewCompat.addWebMessageListener(webView, "PearlAndroid", setOf("https://pearlhub.net"), …)`
exposes `window.PearlAndroid` to frames of that origin only (the main page and the wallet
iframe, never the Turnstile iframe).

Request (JS → native), a JSON string:

```json
{ "id": "r12", "method": "wallet.secret", "params": { "title": "…" } }
```

Reply (native → the same frame, through the listener's `replyProxy`), a JSON string:

```json
{ "id": "r12", "ok": true, "result": { "secret": "…" } }
{ "id": "r12", "ok": false, "error": "cancelled" }
```

Error codes: `unsupported` (unknown method), `bad_params`, `cancelled`, `failed`,
`unavailable`, `no_lock_screen`, `no_secret`, `lockout`, `denied`, and for `safetrade.request`
`no_credentials`, `unreached`, `unconfirmed`.

Native → page events are delivered with `evaluateJavascript` on the main frame:
`window.__pearlEvent && window.__pearlEvent("<name>", <json>)`. Events: `resume`, `pause`,
`notification` (`{ "route": "workers" }` when a notification or widget is tapped while the app
runs; routes are `wallet`, `trade`, `market`, `pools`, `calculator`, `workers`, `settings`, `alerts`, `pro`),
`pushToken` (`{ "token": "…" }` when FCM rotates the token), `live` (`{ "running": false }` when the
person dismissed the 锁屏盯盘 ticker), `billing` (`{ "purchases": [ … ] }`, Play build: a purchase finished while no
`billing.purchase` was waiting, such as a pending payment approved later), `keyboard`
(`{ "visible": true }` when the soft keyboard opens, `false` when it closes; the page hides its
bottom tab bar meanwhile, because the shell already resizes the WebView to sit above the keyboard).

Back button: the shell runs `window.__pearlBack ? window.__pearlBack() : false` on the main
frame. `true` means the page handled it (closed a dialog, left a sub-page). Anything else →
`moveTaskToBack(true)`.

## Methods

| Method | Params | Result |
|---|---|---|
| `info` | – | `{ flavor: "play"\|"direct", versionName, versionCode, sdkInt, fcm: bool, deviceSecure: bool, icon, phone: bool, lockPortrait: bool, launches, firstLaunch, storeReview: bool, widgetPlaced: bool, billing: bool }` — `fcm` is true when Firebase is configured in this build and Google Play services are available; `icon` is `pearl` \| `modern` \| `classic`; `phone` is a smallest width under 600 dp; `launches` / `firstLaunch` (Unix seconds) are the iOS ReviewPrompt counters; `storeReview` is true in the Play build; `widgetPlaced` is any Pearl Hub widget on the home screen (the wallet's 桌面小组件 hint hides then, as on iOS); `billing` is true in the Play build (Google Play Billing) |
| `openExternal` | `{ url }` | `{}`; only `http`, `https`, `mailto`, `tg` |
| `share` | `{ text }` | `{}` — system share sheet |
| `clipboard.write` | `{ text, sensitive?: bool }` | `{}`; `sensitive` marks the clip `EXTRA_IS_SENSITIVE` on API 33+ |
| `clipboard.read` | – | `{ text }` (empty string when the clipboard has no text) |
| `clipboard.clear` | `{ text }` | `{ cleared: bool }` — clears the clipboard only if it still holds `text` (a pasted recovery phrase once the wallet is saved, as iOS does) |
| `ui.theme` | `{ background: "#rrggbb", dark: bool }` | `{}` — window/WebView background and light/dark system-bar icons |
| `ui.secure` | `{ on: bool }` | `{}` — adds/clears `FLAG_SECURE` (no screenshots, blank in recents) |
| `ui.portrait` | `{ lock: bool }` | `{ lockPortrait }` — 设置 → 外观 → 锁定竖屏; on by default, phones only (smallest width under 600 dp), like the iPhone-only iOS switch |
| `icon.set` | `{ icon: "pearl"\|"modern"\|"classic" }` | `{ icon }` — 设置 → 外观 → 应用图标: enables that launcher `activity-alias` and disables the others (`AppIcon.kt`) |
| `review.request` | – | `{ asked: bool }` — Play's in-app review sheet if the iOS gates pass (≥ 3 launches, ≥ 3 days, once per version, 120 days apart); `false` in the direct build |
| `review.open` | – | `{}` — the Play Store page (设置 → 关于 → 为 Pearl Hub 评分); `unsupported` in the direct build |
| `update.check` | – | `{ available: bool, versionCode }` — whether Google Play has a newer version of this install (Play In-App Updates; the wallet's "new version" banner). `available` is false when Play can't tell (an install that didn't come from Play) and always in the direct build, which reads `pearlhub.net/downloads/android/latest.json` itself |
| `update.start` | – | `{}` — Play's full-screen (immediate) update, or the store page when Play won't run it; `unavailable` in the direct build. An update left mid-way resumes when the app comes back to the foreground |
| `billing.product` | – | `{ productId, price, currency, micros }` — Pro (`com.prl.wizard.pro`, one-time) as Play prices it for this Google account (`price` is Play's formatted string). `unavailable` when Play Billing can't be reached or the product is not on Play (sideloaded or emulator builds); `unsupported` in the direct build |
| `billing.purchase` | – (waits on the person) | `{ productId, purchaseToken, orderId, state: "purchased"\|"pending", acknowledged }` from Play's purchase sheet. If the account already owns Pro, it returns that purchase (a restore). Errors: `cancelled` (sheet closed or its activity went away), `unavailable` (including a lost Billing connection), `failed`. The shell does not acknowledge: the page sends `purchaseToken` to `POST /v1/purchase/google`, and the Worker checks it with Google and acknowledges it |
| `billing.owned` | – | `{ purchases: [ …as above ] }` — the Pro purchases this Google account owns (恢复购买, and the launch-time check that unlocks a reinstall like iOS `Transaction.currentEntitlements`). The shell also queries on resume and forwards owned purchases through `billing`, recovering payments completed while disconnected |
| `file.save` | `{ name, mime, text }` | `{}` after the system's "save as" screen wrote the file; `cancelled` when the person backed out or the activity leaves the foreground before the picker opens (the account's settings export). Text is staged in private no-backup storage, with only its filename in saved state, so a recreated activity can still finish the export; the staged copy is removed on completion or cancellation |
| `live.status` | – | `{ running, style, startedAt, canShow }` — the 锁屏盯盘 ticker (see below) |
| `live.start` | `{ title, label1h, label24h, style?: "full"\|"lock"\|"island", state }` | `{ running: true, startedAt }` — asks for notification permission first; `denied` when notifications or the channel are off. While running, starts again only to redraw (new style or language), keeping `startedAt` |
| `live.update` | `{ state }` | `{ running }` — a newer price; ignored when the ticker is off or `state.at` is older than the shown price |
| `live.stop` | – | `{}` |
| `haptic` | `{ kind: "light"\|"success"\|"warning" }` | `{}` |
| `notifications.status` | – | `{ permission: "granted"\|"denied"\|"default" }` |
| `notifications.request` | – | `{ permission }` after the API 33+ runtime prompt (`granted` below 33 when notifications are enabled) |
| `notifications.settings` | – | `{}` — opens this app's notification settings |
| `push.token` | – | `{ token }` — FCM registration token; `unavailable` without Firebase/Play services |
| `push.delete` | – | `{}` — deletes the FCM token |
| `wallet.status` | – | `{ hasSecret: bool, deviceSecure: bool }` |
| `wallet.secret` | `{ title, subtitle?, create?: bool }` | `{ secret }` after biometric / screen-lock confirmation. Creates a secret after confirmation when `create` is true and none exists; `no_secret` when none exists and `create` is false, or a reset invalidated the request; `no_lock_screen` when the device has no screen lock |
| `wallet.clear` | – | `{}` — deletes the stored secret and its Keystore key |
| `settings.security` | – | `{}` — opens the system screen-lock settings |
| `net.post` | `{ url, body, contentType }` | `{ status, body }` — only `https://blockbook.pearlresearch.ai/api/*` and `https://blockbook.testnet.pearlresearch.ai/api/*` |
| `nock.rpc` | `{ node, path, body, timeout? }` | `{ status, body, grpcStatus, grpcMessage }` — one gRPC-web POST (`application/grpc-web+proto`, `X-Grpc-Web: 1`, no redirects, no cookies). `node` only `http://216.158.95.10:5556`; `path` only `nockchain.public.v2.NockchainService/` `WalletGetBalance`, `WalletSendTransaction`, `TransactionAccepted` and `nockchain.public.v2.NockchainBlockService/` `GetTransactionDetails`, `GetBlocks`, `GetBlockDetails`; `body` is the framed request in standard base64, at most 1 MiB decoded; `timeout` in ms, clamped to 1000–60000, default 30000. Every HTTP status comes back with its body (base64, at most 16 MiB). `grpcStatus` / `grpcMessage` are the response headers of a trailers-only reply (`grpc-message` still percent-encoded), else `null`; the page parses trailer frames in `body`. `bad_params` for anything else, `failed` on a network error |
| `safetrade.keys` | – | `{ set: bool, masked: string\|null }` — whether a SafeTrade API key pair is saved; `masked` is the iOS form of the key (`abcd••••yz`, `••••` for 6 characters or fewer). A saved pair the Keystore can never decrypt again (its key gone or invalidated, the ciphertext failing its tag) is deleted the first time a signed call reads it, which then fails `no_credentials`, so this stops reporting it. The pair is device-only: one AES-256-GCM ciphertext under its own Android Keystore key (StrongBox when available, no user-authentication binding, like the iOS Keychain item) in no-backup storage (`SafeTrade.kt`). Neither key nor secret is ever returned to the page |
| `safetrade.save` | `{ key, secret }` | `{ masked }` — trims both, replaces the saved pair. `bad_params` unless each is 1–256 printable ASCII characters without whitespace; `failed` when the Keystore or the write fails. The page checks typed keys first with `safetrade.request` `auth: "explicit"` |
| `safetrade.clear` | – | `{}` — deletes the pair and its Keystore key |
| `safetrade.request` | `{ method, path, query?, form?, json?, auth?: "none"\|"stored"\|"explicit", key?, secret?, title?, subtitle? }` | `{ status, body }` — one request to `https://safetrade.com`, signed and sent by the shell because SafeTrade's private API refuses the WebView's CORS preflight. Every HTTP status comes back with its body (UTF-8 text, at most 8 MiB); 20 s timeouts, no redirects, no cookies, no cache. Only the iOS app's calls (`SafeTrade.kt`): `GET` `/api/v2/peatio/public/markets/<m>/tickers`, `/api/v2/trade/public/markets/<m>` (+ `/depth`, `/k-line`), `/api/v2/trade/public/currencies/<c>`, `/api/v2/trade/account/` `balances/spot`, `withdraws`, `deposit_address/<c>`, `deposits`, `beneficiaries`, `/api/v2/trade/market/orders`, `/cdn-cgi/trace`; `POST` `/api/v2/trade/market/orders`, `/api/v2/trade/market/orders/<id>/cancel`, `/api/v2/trade/account/withdraws/generate_code`, `/api/v2/trade/account/withdraws` (`<m>` `[a-z0-9]{3,32}`, `<c>` `[a-z0-9]{1,32}`, `<id>` 1–20 digits; nothing else, no `..`, no query in `path`). `query` (`GET` only) and `form` (`POST`, sent `application/x-www-form-urlencoded`) are objects of at most 16 strings, names `[a-z_]{1,32}`, values up to 64 printable ASCII characters without space, `&` or `#`; `json` (`POST`, sent `application/json`, at most 16 KiB) is the body's JSON text, checked to be exactly one object and sent as written (the page writes amounts as plain decimals, as iOS sends them); an object is also accepted but re-serialized by org.json, which writes 0.0005 as `5.0E-4`; never both. `auth` defaults to `none`; `/api/v2/trade/account/*` and `/api/v2/trade/market/*` need `stored` (the saved pair) or `explicit` (`key` / `secret` as for `safetrade.save`, only for `GET …/balances/spot`). Signed calls carry `X-Auth-Apikey`, `X-Auth-Nonce` and `X-Auth-Signature` = lowercase hex HMAC-SHA256(secret, nonce + key); the nonce is milliseconds on SafeTrade's clock (the device offset is learned from each reply's `Date` and applied when it is 2 s or more), increasing between replies; SafeTrade's gateway (Barong) only checks that it is within its lifetime of the server's time and keeps no record of earlier nonces, so once a reply shows the last nonce more than 1 s ahead of SafeTrade's time (a fast clock, or one set back) the next starts again from SafeTrade's time; a non-2xx whose `errors` key has the token `nonce` is signed again and sent once more (the gateway refused it before acting, so this holds for a `POST` too). The withdrawal (`POST /api/v2/trade/account/withdraws`) needs `auth: "stored"` and `title` (≤ 64, `subtitle` ≤ 128): the shell asks for biometrics / the screen lock itself, as `wallet.secret` does and as iOS does before every withdrawal, and sends only after it succeeds (its error code otherwise: `cancelled`, `no_lock_screen`, `lockout`, …). Errors: `bad_params`; `no_credentials` (`stored` without a saved pair, checked before any prompt or network); `unreached` (the connection was never made — offline, DNS, refused, connect timeout, TLS handshake — or a `GET` failed again after one redial; nothing reached SafeTrade); `unconfirmed` (a `POST` failed after connecting — writing, waiting or reading — so the exchange may have acted: the page reconciles with the order / withdrawal list and must not invite a blind retry); `failed`. For the withdrawal, every other code (`failed`, `unavailable`, …) is the device check's: a failure to send after it passed is `unreached` |
| `widgets.pin` | `{ kind: "hub" \| "price" \| "quote" \| "change1h" \| "change24h" \| "quoteMining" \| "coverMarket" \| "coverMining" }` | `{}` after asking the launcher to place that widget on the home screen (`requestPinAppWidget`); `unsupported` when the launcher cannot or the app is not in the foreground; `bad_params` for another kind. Cover-screen placement uses the system's own widget panel |
| `widgets.update` | `{ part: "app" \| "wallet", data }` | `{}` — stores the home-screen widget snapshot and redraws the widgets (see below); `bad_params` without `data`, for another part, or for `app` data that is not an object |

Play purchase and update screens only launch while their activity is resumed. A destroyed activity
releases its pending purchase callback, so a later Play result reaches the current page through
`billing`. A resume query that finds a purchase also settles an outstanding `billing.purchase`
request; the page is not left waiting for a sheet callback that was lost with the connection.
Review entry and the delayed review launch also require a resumed activity; a background request
does not consume review eligibility. Custom JavaScript dialogs are closed with their WebView when
its renderer is replaced. Only a popup's main-frame navigation opens an external application.

The Play web flow captures its account session across native and server waits. Account switches
invalidate old continuations, and failed automatic entitlement reconciliation can retry on resume.
Verification followed by a failed membership-profile read reports recovery rather than success.

### The wallet secret

The wallet keeps the browser extension's vault (AES-256-GCM, PBKDF2-SHA256). On Android its
password is not typed: it is a random 32-byte secret (base64url) that the shell creates,
encrypts with an AES-256-GCM key in the Android Keystore (StrongBox when available, no
user-authentication binding so new fingerprints do not destroy it) and keeps in no-backup
storage. The shell releases it only after `BiometricPrompt` succeeds with
`BIOMETRIC_STRONG | DEVICE_CREDENTIAL` (API 30+; `BIOMETRIC_WEAK | DEVICE_CREDENTIAL` on
28–29; the keyguard confirm-credential screen on 26–27). This mirrors the iOS app: the
recovery phrase lives in the Keychain (`WhenUnlockedThisDeviceOnly`) behind a Face ID /
passcode check. Prompt text comes from the page, so it follows the app language.

Authentication must start from a resumed activity before its fragment state is saved; otherwise
it returns `cancelled`. Clearing the wallet invalidates outstanding authentication requests,
including requests allowed to create a secret. A read-only unlock never creates a replacement
secret, and concurrent creation requests share the existing secret.
Destroying an activity closes its bridge executors after accepted work drains. Late authentication
success cannot submit new work through that closed bridge.

## Push payload (FCM data message)

All values are strings: `title`, `body`, `kind` (`price` | `offline` | `income` | `test`), `tag`
(collapse key; for `price` and `test` the same tag replaces the shown notification; `offline` alerts stack in one `worker-offline` group, retaining the latest 20; `income` (到账提醒, a payment to the watched address, tag `income:<txid tail>`) stacks in an `income` group, retaining the latest 10), `route` (`market` | `workers` |
`alerts` | `wallet`; a payment opens `wallet`, also when the route is missing). Channels: `price` for `price`/`test`, `offline` for `offline`, `income` for `income`.

Offline alerts retain the latest 20 entries, payments the latest 10, in reusable notification slots.
This bounds unread history below Android's per-app notification limit, leaving room for price alerts
and the ticker. Only the child alert makes sound; its group summary stays silent.

到账提醒's address rides on the page's push registration (`income` in `PUT /v1/web/push`: `{ addr, label }`
while it is on, `null` to drop it); it is kept on the device only (`pearlhub.incomeAlert` in the page's
storage), as iOS keeps it in iCloud and not in the synced account configuration.

`kind` = `live` is the 锁屏盯盘 price instead (`usd`, `change1h`, `change24h`, `at`; a change is
missing when unknown, `tag` is `live`): it updates the ticker if it runs and is dropped otherwise.

## Live ticker (锁屏盯盘)

The iOS Live Activity (prl-hub `Sources/App/PriceLiveActivity.swift`, Pro) as an ongoing
notification (`LiveTicker.kt`, channel `live`: default importance without sound, so lock screens
that hide silent notifications still show it). It shows the PRL price and its 1-hour and 24-hour
change in the iOS formats (`$%.2f`, `%+.2f%%`, `—` when unknown) with the price's time, opens
`market` when tapped, and asks Android 16+ to promote it to a Live Update (status-bar chip). Styles
mirror iOS: `full` (card, price in the chip), `lock` (card, the chip shows only the pearl) and
`island` (price in the chip, a one-line card). It ends 8 hours after `startedAt` (the notification's
timeout), on `live.stop`, or when the person dismisses it (event `live`).
Foreground and push updates share the same state lock, so stopping cannot race with a push that
reposts the notification. Redrawing the style or language keeps whichever price has the newer time.
Dismissal is tied to its ticker session, so an old notification's delayed delete callback cannot
stop a newer session. A pending notification-permission result is cancelled by a newer start or
stop request.

`state` is `{ usd, change1h, change24h, at }` (Unix seconds), the iOS ContentState. The page sends
each new app-wide price while it runs; while it does not, the alert Worker pushes its
once-a-minute price (`kind` = `live` above) to the phone's FCM registration, which the page
registers like the iOS activity's push token: `PUT /v1/live/<hex sha256("fcm:" + token)>` with
`{ topic: "android", env: "production", fcm: { token }, started_at }`, and `DELETE` the same path when
the ticker ends (prl-hub-server `alerts/src/index.js` `putAndroidLive`). The page owns Pro: it stops the
ticker when Pro lapses.

The page re-registers when push capability becomes ready or FCM rotates its token. Worker PUTs
and DELETEs are serialized, and failed withdrawals remain in local storage for retry on resume.
A failed native stop keeps the active registration; a start whose refreshed status is already off
does not register. A failed status read defers registration until a later resume.
Ordinary price/offline-alert registration also serializes PUT/DELETE and checks its account session
after token waits. It saves the exact hashed registration key before PUT, allowing cleanup even if
the response is lost. Turning off ordinary alerts deletes their Worker record and keeps the shared
FCM transport available to an independently running ticker.

## Widget snapshot (`widgets.update`)

All eight widget providers share this contract. `change1h` and `change24h` need only price;
`coverMarket` shows price with both changes, and `coverMining` needs price and pools. The glance
cards resize between mini, one-row, regular and whole-panel layouts. These are standard AppWidgets,
usable by home/cover-screen hosts; Samsung's extra discovery metadata on the two glance cards
does not change the data or rendering protocol. No wallet data is needed for these seven public
price/mining widgets. Only Pearl Hub reads the wallet part.

The page publishes what the iOS app writes to its App Group snapshot (`prl-hub`
`Sources/WidgetShared/WidgetSnapshot.swift`, `WidgetBridge.swift`). The shell keeps the latest
`app` and `wallet` parts, redraws the widgets at once, and refreshes them itself while the app
is closed (price from `GET https://pearlhub.net/v1/price`, balance from Blockbook, pools from
`POST https://pearlhub.net/v1/pool/stats`). Times are Unix seconds; PRL amounts are atom strings
(1 PRL = 100,000,000).

```json
{ "part": "app", "data": {
  "lang": "zh-Hans", "theme": "system", "palette": "classic", "pro": true,
  "price": { "usd": 1.85, "change1h": -1.07, "change24h": 4.52, "at": 1792116928 },
  "fiat": { "code": "CNY", "symbol": "¥", "decimals": 2, "rate": 7.1 },
  "pools": [ { "label": "Rig A", "pool": "AlphaPool", "monitorKey": "AlphaPool|prl1…|", "hashrate": 1230000000, "online": 3, "total": 4 } ],
  "monitors": [ { "pool": "AlphaPool", "addr": "prl1…", "acct": null, "label": "Rig A" } ],
  "poolsAt": 1792116928,
  "at": 1792116928 } }

{ "part": "wallet", "data": {
  "name": "My wallet", "network": "mainnet", "xpub": "zpub…", "changeAddresses": ["prl1…"],
  "balance": "6361479714", "pending": "0",
  "recent": [ { "received": true, "amount": "100000000", "time": 1792116000 } ],
  "at": 1792116928 } }

{ "part": "wallet", "data": null }
```

`theme` is the app's choice (`system`, `light` or `dark`); `fiat` is null when the secondary
currency is hidden; `pools` are the unpaused monitors' last real-time figures; `monitors` are
their targets, for the shell's own refresh. `wallet` is null when no wallet exists, and is only
published while the wallet is unlocked (the public key comes from the unlocked vault). Medium and
large Pearl Hub widgets need Pro (`pro`), as on iOS. Widget taps open a route like a
notification does (cold start `#<route>`, warm start the `notification` event): the price opens
`trade` (the 交易 tab, as iOS WidgetDeepLink.trade), the wallet figures `wallet`, the pools `workers`, and a locked widget `pro` (the 设置
tab with the Pro introduction).

`poolsAt` is the oldest successful pool-stat timestamp among the current targets, or zero if any
target has no data. It is independent of the app publication time `at`, so a theme or price update
cannot make old mining figures appear fresh. Older publishers without `poolsAt` fall back to `at`.
`monitorKey` joins the trimmed pool, address and account with `|` (limits 40/200/64 characters);
it distinguishes targets sharing a label and pool. Native caches carry a source fingerprint to
reject stale results even when two edits share the same second; older caches without one are
rebuilt. A partial pool refresh retains the latest valid cached figure for each failed target.
Pool-cache identity includes the pools, targets and `poolsAt`; unrelated price, language and theme
publications preserve it. A fetched balance is tied to the entire published wallet snapshot.
Concurrent native reads carry ordered request numbers; an older request cannot replace a newer
accepted result, and partial results merge with the current cache while storage is locked. Retry
claims are atomic and distinguish changed sources. A publication during an active one-shot refresh
retains one queued successor; repeated pending updates coalesce.
Pool freshness uses valid server `ts` when present, including for cached API replies. Invalid source
time, malformed pool results or invalid UTXO confirmations do not become fresh zero figures.
Duplicate keyed targets are counted once in mining totals.

What the shell does with it (`Widgets.kt`, `WidgetRefresh.kt`): fields of the wrong type or out
of range are dropped (an unknown `lang` follows the device language, an unknown `theme` is
`system`, any `palette` but `mono` is `classic`, an `xpub` that is neither base58 nor a PRL
address disables the balance refresh). A read-only wallet (only an address, no seed) publishes the
address it watches as `xpub` with no `changeAddresses`, as iOS does; `/api/v2/utxo/` answers it the same way. Both parts live in an app-private SharedPreferences file that backups exclude.
The refresh runs only while a widget is placed: every ~30 minutes on a network, once after each
`widgets.update`, and when a widget is added. A part published within the last two minutes is
reused instead of fetched; a locked (no Pro) medium or large Pearl Hub widget fetches nothing. The
balance reads Pearl Hub's index (`https://pearlhub.net/node`) first on mainnet, then the official
indexer; testnet uses `blockbook.testnet.pearlresearch.ai`. Each attempt reads the xpub and every
`changeAddresses` entry from one index, starting the whole read over on fallback. The gateway uses
the same public client identifier as `prl-hub-wallet/src/chain.ts`. UTXOs (`/api/v2/utxo/…`) are de-duplicated
by `txid:vout`: confirmed outputs are the balance, unconfirmed ones pending (the wallet's
`loadAccount` rule); `recent` always stays as published. A fetched figure replaces a published
one only if the page has not published that part again meanwhile, and a failed fetch keeps the old
figures, whose age the freshness stamp shows.

The medium Pearl Hub layout uses three pool columns when at least three pools are published,
two otherwise. Every size uses a quiet/gold/rose rigs pill and mutes hashrate when none are online.
The existing `info`, clipboard, file, icon, live and review bridge methods are unchanged. The
wallet frame's `PearlHost.alerts()` also reports whether Pro status is ready, and its subscription
is notified on native resume so the direct APK's update check can run (at most every six hours).
