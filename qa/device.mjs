// End-to-end check of the Android app on an emulator or phone: adb drives the system UI (the
// screen-lock prompt, the back button, screenshots) and raw CDP drives the WebView (Playwright's
// connectOverCDP needs browser-context commands the WebView's DevTools server lacks).
//   node qa/device.mjs [--serial emulator-5554] [--apk path] [--pin 1111]
// Needs a debug build (WebView debugging on). Wipes the app's data (the run creates a fresh
// wallet) and sets the device PIN when none is set. Screenshots go to out/device-qa/.
import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { fileURLToPath } from "node:url";

const arg = (name, fallback) => process.argv.includes(name) ? process.argv[process.argv.indexOf(name) + 1] : fallback;
const ADB = `${process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`}/platform-tools/adb`;
const serial = arg("--serial", "emulator-5554"), pin = arg("--pin", "1111");
const apk = arg("--apk", fileURLToPath(new URL("../app/build/outputs/apk/play/debug/app-play-debug.apk", import.meta.url)));
const out = fileURLToPath(new URL("../out/device-qa/", import.meta.url));
mkdirSync(out, { recursive: true });
const PKG = "com.prl.wizard";
const adb = (...args) => execFileSync(ADB, ["-s", serial, ...args], { encoding: "utf8", maxBuffer: 64 << 20 });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const failures = [];
const check = (ok, label) => { console.log(`${ok ? "✓" : "✗"} ${label}`); if (!ok) failures.push(label); };

// ---- adb helpers
async function bootCompleted() { for (let i = 0; i < 180; i++) { try { if (adb("shell", "getprop", "sys.boot_completed").trim() === "1") return; } catch { /* booting */ } await sleep(1000); } throw new Error("device did not boot"); }
const shot = (name) => { const png = execFileSync(ADB, ["-s", serial, "exec-out", "screencap", "-p"], { maxBuffer: 64 << 20 }); writeFileSync(`${out}${name}.png`, png); return png; };
function dump() { adb("shell", "uiautomator", "dump", "/sdcard/ui.xml"); return adb("shell", "cat", "/sdcard/ui.xml"); }
async function waitUi(pattern, timeout = 15000) {
  for (const end = Date.now() + timeout; Date.now() < end; await sleep(500)) { const xml = dump(); if (pattern.test(xml)) return xml; }
  return null;
}
function tapText(xml, pattern) {
  const node = [...xml.matchAll(/<node [^>]*>/g)].map((m) => m[0]).find((n) => pattern.test(n));
  const b = node?.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  if (!b) return false;
  adb("shell", "input", "tap", String((+b[1] + +b[3]) >> 1), String((+b[2] + +b[4]) >> 1));
  return true;
}
// The screen-lock confirmation (BiometricPrompt with DEVICE_CREDENTIAL; PIN pad when no biometrics).
async function enterPin() {
  const xml = await waitUi(/com\.android\.systemui|PIN|password/i, 20000);
  if (!xml) return false;
  await sleep(700);
  adb("shell", "input", "text", pin); adb("shell", "input", "keyevent", "66");
  return true;
}
const foreground = () => /mResumedActivity.*com\.prl\.wizard|topResumedActivity.*com\.prl\.wizard/.test(adb("shell", "dumpsys", "activity", "activities"));
const blank = (png) => png.length < 40000;

// ---- CDP
let ws, seq = 0;
const pending = new Map(), logs = [];
async function connect() {
  let targets;
  for (let i = 0; i < 40; i++) {
    try {
      const pid = adb("shell", "pidof", PKG).trim();
      execFileSync(ADB, ["-s", serial, "forward", "tcp:9334", `localabstract:webview_devtools_remote_${pid}`]);
      targets = await (await fetch("http://127.0.0.1:9334/json")).json();
      if (targets.some((t) => t.type === "page" && t.url.startsWith("https://pearlhub.net/?"))) break;
    } catch { /* not up yet */ }
    await sleep(500);
  }
  const target = targets.find((t) => t.type === "page" && t.url.startsWith("https://pearlhub.net/?"));
  ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  ws.onmessage = (m) => {
    const d = JSON.parse(m.data);
    if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); }
    if (d.method === "Runtime.exceptionThrown") logs.push(`exception: ${d.params.exceptionDetails?.exception?.description || d.params.exceptionDetails?.text}`);
    // Turnstile's own frame logs its internal CSP probes; they are Cloudflare's, not the app's.
    if (d.method === "Log.entryAdded" && d.params.entry.level === "error" && !/Failed to load resource/.test(d.params.entry.text) && !/challenges\.cloudflare\.com/.test(d.params.entry.url || "")) logs.push(`log: ${d.params.entry.text} ${d.params.entry.url || ""}`);
    if (d.method === "Runtime.consoleAPICalled" && d.params.type === "error" && !/font-size:0;color:transparent/.test(JSON.stringify(d.params.args))) logs.push(`console: ${d.params.args.map((a) => a.value ?? a.description).join(" ")}`);
  };
  await send("Runtime.enable"); await send("Log.enable");
}
const send = (method, params = {}) => new Promise((resolve) => { const id = ++seq; pending.set(id, resolve); ws.send(JSON.stringify({ id, method, params })); });
async function js(expression) {
  const r = await send("Runtime.evaluate", { expression: `(async () => { const W = document.querySelector(".wallet-frame")?.contentDocument; return (${expression}); })()`, awaitPromise: true, returnByValue: true });
  if (r.result?.exceptionDetails) throw new Error(`${expression}: ${r.result.exceptionDetails.exception?.description || r.result.exceptionDetails.text}`);
  return r.result?.result?.value;
}
async function until(expression, timeout = 20000) {
  for (const end = Date.now() + timeout; Date.now() < end; await sleep(300)) { try { if (await js(`!!(${expression})`)) return true; } catch { /* page busy */ } }
  return false;
}
// Click the first element whose text matches, inside the wallet frame (W) or the page (document).
const click = (scope, selector, text = "") => js(`(() => { const el = [...${scope}.querySelectorAll(${JSON.stringify(selector)})].find((e) => e.textContent.includes(${JSON.stringify(text)}) && !e.disabled); if (!el) return false; el.click(); return true; })()`);
const tab = (i) => js(`document.querySelectorAll(".sidebar nav .nav-item")[${i}].click()`);
// A 设置 row by its label (the first text node, before any detail line).
const row = (label) => js(`(() => { const r = [...document.querySelectorAll(".android-row")].find((e) => e.querySelector(".android-row-label")?.firstChild?.textContent.trim() === ${JSON.stringify(label)}); r?.click(); return !!r; })()`);
// A raw bridge call (BRIDGE.md), for native pieces the page only reaches behind Pro or a server account.
const bridge = (method, params = {}) => js(`new Promise((resolve) => { const id = "qa-" + Math.random().toString(36).slice(2); const ch = window.PearlAndroid; const on = (e) => { let r; try { r = JSON.parse(e.data); } catch { return; } if (r.id !== id) return; ch.removeEventListener("message", on); resolve(r); }; ch.addEventListener("message", on); ch.postMessage(JSON.stringify({ id, method: ${JSON.stringify(method)}, params: ${JSON.stringify(params)} })); })`);
const notification = () => adb("shell", "dumpsys", "notification", "--noredact").split(/\n(?=\s*NotificationRecord\()/).find((r) => r.includes(`pkg=${PKG}`) && /tag=live\b/.test(r)) || "";
const launcherAliases = () => adb("shell", "cmd", "package", "query-activities", "--brief", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER").split("\n").filter((l) => l.includes(PKG)).map((l) => l.trim().split("/").pop().replace(/^\./, ""));
const portrait = () => js(`innerHeight > innerWidth`);

// ---- run
await bootCompleted();
// A device proxy on 127.0.0.1:<port> (README "Emulator QA") reaches the host through adb, which forgets the tunnel on reboot.
const proxy = adb("shell", "settings", "get", "global", "http_proxy").trim().match(/^127\.0\.0\.1:(\d+)$/);
if (proxy) adb("reverse", `tcp:${proxy[1]}`, `tcp:${proxy[1]}`);
adb("install", "-r", apk);
adb("shell", "pm", "clear", PKG);
try { execFileSync(ADB, ["-s", serial, "shell", "locksettings", "set-pin", pin], { stdio: "ignore" }); } catch { /* already set */ }
adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS");
adb("shell", "input", "keyevent", "224"); adb("shell", "wm", "dismiss-keyguard"); await sleep(800);
// A locked screen (after a reboot, or left locked) must be unlocked with the PIN, or the app starts behind
// it and its screen-lock prompts fail. Android 16 shows the PIN pad only after a swipe up.
for (let i = 0; i < 3 && /mKeyguardShowing=true/.test(adb("shell", "dumpsys", "activity", "activities")); i++) {
  adb("shell", "input", "keyevent", "224"); await sleep(400);
  adb("shell", "input", "swipe", "540", "1900", "540", "500", "250"); await sleep(1000);
  adb("shell", "input", "text", pin); adb("shell", "input", "keyevent", "66"); await sleep(1500);
}
check(!/mKeyguardShowing=true/.test(adb("shell", "dumpsys", "activity", "activities")), "screen unlocked before the run");
adb("shell", "am", "start", "-W", "-n", `${PKG}/.MainActivity`);
await connect();

check(await until(`document.querySelector(".wallet-frame") && W?.querySelector("button.primary")`), "app opens on the wallet tab");
const tabs = await js(`[...document.querySelectorAll(".sidebar nav .nav-label-full")].map((e) => e.textContent.trim())`);
check(tabs?.length === 5 && tabs[1] === "Trade", `five tabs: ${tabs?.join(" | ")}`);
shot("01-welcome");

// Create a wallet in the iOS app's three steps (10-29): name, the phrase, then the check. The words must be unscreenshottable.
await click("W", "button", "Create New Wallet");
check(await until(`[...W.querySelectorAll("button")].some((b) => b.textContent.includes("Generate Recovery Phrase"))`), "create starts on the name step");
await click("W", "button", "Generate Recovery Phrase");
check(await until(`W.querySelectorAll(".seed-word").length === 12`), "twelve recovery words");
await sleep(600);
check(blank(shot("02-words-secure")), "FLAG_SECURE blanks the screenshot of the recovery words");
const words = await js(`[...W.querySelectorAll(".seed-word")].map((d) => d.textContent.trim())`);
await click("W", "button", "I've Backed It Up Safely");
// iOS's check: three random positions, pick each word among the choices.
check(await until(`W.querySelectorAll(".quiz-card").length === 3`), "backup check asks for three random words, multiple choice");
await js(`(() => { const words = ${JSON.stringify(words)}; for (const c of W.querySelectorAll(".quiz-card")) { const n = Number(c.querySelector("p").textContent.match(/\\d+/)[0]); [...c.querySelectorAll(".quiz-options button")].find((b) => b.textContent.trim() === words[n - 1]).click(); } return true; })()`);
await sleep(300);
await click("W", "button", "Confirm and Create");
check(await enterPin(), "screen-lock prompt confirmed with the PIN");
check(await until(`W.querySelector(".balance-card")`, 30000), "wallet created and unlocked");
await until(`W.querySelector(".balance-status") && !/Refresh/.test(W.querySelector(".balance-status").textContent)`, 30000);
shot("03-wallet");
// As on iOS, the home shows no address: it is under Receive.
check(await js(`!W.querySelector(".address-card")`), "wallet home shows no address card");
check(await until(`/[0-9]/.test(W.querySelector(".balance-head")?.textContent || "")`, 30000), "balance read from Blockbook through the shell's proxy");

// The iOS wallet home: the last transfers, and one-line links to 价格提醒 and 桌面小组件.
check(await until(`W.querySelector(".recent-card") && W.querySelectorAll(".shortcut-card > button.shortcut-row").length === 2`), "wallet home: recent transfers card, price-alert and widget links");
// The iOS send screen's inline checks.
await click("W", ".balance-actions button", "Send");
await until(`W.querySelector("dialog[open] input[aria-label]")`);
await js(`(() => { const i = [...W.querySelectorAll("dialog[open] input")].find((e) => e.placeholder?.startsWith("prl1")); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(i, "prl1nope"); i.dispatchEvent(new Event("input", { bubbles: true })); return true; })()`);
await sleep(400);
check(await js(`!!W.querySelector("dialog[open] .field-error") && [...W.querySelectorAll("dialog[open] button.primary")].pop().disabled && !!W.querySelector("dialog[open] .field-note")`), "send: bad address explained inline, preview held back, available balance shown");
adb("shell", "input", "keyevent", "4"); await sleep(500);

// The receiving address, copied through the shell's clipboard.
await click("W", ".balance-actions button", "Receive");
await until(`W.querySelector("dialog[open] .receive code")`);
const address = await js(`W.querySelector("dialog[open] .receive code").textContent`);
check(/^prl1/.test(address || ""), "receive address shown");
await click("W", "dialog[open] .receive button.primary");
check(await js(`navigator.clipboard.readText()`) === address, "copied address is on the system clipboard");
adb("shell", "input", "keyevent", "4"); await sleep(500);

// Back closes a wallet dialog, then leaves other tabs, then backgrounds the app.
await click("W", ".balance-actions button");
await until(`W.querySelector("dialog[open]")`);
adb("shell", "input", "keyevent", "4"); await sleep(600);
check(await js(`!W.querySelector("dialog[open]")`) && foreground(), "back closes the receive dialog");
await tab(4); await until(`document.querySelector(".android-settings")`);
shot("04-settings");
adb("shell", "input", "keyevent", "4"); await sleep(600);
check(await js(`!document.querySelector(".wallet-frame").hidden`), "back from Settings returns to the wallet");

// Mining tab: live data, SafeTrade candlesticks straight from the WebView.
await tab(2);
check(await until(`/\\$\\d/.test(document.querySelector("main")?.textContent || "")`, 30000), "market shows the live price");
// SafeTrade is fetched by the WebView itself; on a network whose DNS poisons safetrade.com
// (seen on the development Mac) it fails exactly as it does on the website, so report it.
if (await until(`document.querySelectorAll(".kline-body").length > 20 || document.querySelector(".kline-panel .chart-empty")`, 30000) && await js(`document.querySelectorAll(".kline-body").length > 20`)) check(true, "candlestick chart drawn (SafeTrade reached from the WebView)");
else console.log(`! SafeTrade candlesticks did not load on this network: ${await js(`fetch("https://safetrade.com/api/v2/trade/public/markets/pearlusdt/k-line?period=60&limit=1").then((r) => "HTTP " + r.status, (e) => e.message)`)}`);
shot("05-market");
check(await until(`document.querySelector(".fleet-only .fleet-hero")`), "overview shows the fleet card (iOS 每天的情况 · 我的全部设备)");
await js(`document.querySelectorAll(".android-segments button")[1].click()`); await sleep(2500); shot("06-pools");
await js(`document.querySelectorAll(".android-segments button")[2].click()`); await sleep(1200); shot("07-returns");
await tab(3); await sleep(1200); shot("08-monitor");

// Lock, then unlock with the screen lock.
await tab(0);
await click("W", ".header-lock");
check(await until(`W.querySelector(".welcome.locked")`), "wallet locks");
shot("09-locked");
await click("W", "form button.primary");
check(await enterPin(), "unlock prompt");
check(await until(`W.querySelector(".balance-card")`, 20000), "unlocked with the screen lock");

// ---- iOS parity (prl-hub Sources/Settings): 设置's sections and pages, and the native pieces behind them.
await tab(4); await until(`document.querySelector(".android-settings")`);
const sections = await js(`[...document.querySelectorAll(".android-group > h2")].map((h) => h.textContent.trim()).join("|")`);
check(sections === "Account & Wallet|Pro Membership|Notification Settings|Appearance Settings|Widget|Language & Currency|Gateway Settings|Security & Privacy|Help & About", `settings sections as on iOS (+ Widget): ${sections}`);
const labels = await js(`[...document.querySelectorAll(".android-row-label")].map((e) => e.firstChild.textContent.trim())`);
const missing = ["Email account", "Wallet & Security", "Membership Benefits", "Price & Payment Alerts", "Offline Alerts", "Lock Screen live price", "Theme & Colors", "App Icon", "Pearl Network", "About"].filter((l) => !labels.includes(l));
check(!missing.length, `iOS settings rows present${missing.length ? "; missing " + missing.join(", ") : ""}`);
check(await js(`document.querySelector(".android-row .badge")?.textContent === "Pro"`), "Pro-only rows carry the Pro badge without Pro");

await row("About");
check(await until(`document.querySelector(".android-page-title")?.textContent === "About"`), "About page");
// iOS 6a1e720 dropped the self-custody line from About.
check(await js(`["Cyber Corner", "Rate Pearl Hub", "Open source on GitHub"].every((t) => document.querySelector("main").textContent.includes(t))`), "About: developer, rating (Play), GitHub");
await row("Open Source Licenses");
check(await until(`document.querySelectorAll(".android-license").length >= 9`), "open-source licenses page");
shot("14-licenses");
adb("shell", "input", "keyevent", "4"); await sleep(500);
check(await js(`document.querySelector(".android-page-title")?.textContent === "About"`), "back from Licenses returns to About");
adb("shell", "input", "keyevent", "4"); await sleep(500);
check(await js(`!document.querySelector(".android-page-title") && !!document.querySelector(".android-group")`) && foreground(), "back from About returns to Settings");

// 会员权益 and 锁屏盯盘 without Pro: the iOS paywall card, no checkout in the Play build.
await row("Membership Benefits");
// The Play build may offer Google Play Billing but never the website's PRL checkout.
check(await until(`document.querySelector(".android-pro-card")`) && await js(`![...document.querySelectorAll(".android-pro-card button.primary")].some((b) => b.textContent.includes("PRL")) && document.querySelector("main").textContent.includes("Already have Pro? Sign in")`), "Pro page: paywall without the PRL checkout, sign-in to restore");
shot("15-pro");
adb("shell", "input", "keyevent", "4"); await sleep(400);
await row("Lock Screen live price");
check(await until(`document.querySelector(".android-pro-card")`), "lock-screen ticker page asks for Pro");
adb("shell", "input", "keyevent", "4"); await sleep(400);
await row("Offline Alerts");
check(await until(`document.querySelector(".android-page-title")?.textContent === "Offline Alerts" && document.querySelector(".android-pro-card")`), "offline alerts page (iOS OfflineAlertsView) asks for Pro");
adb("shell", "input", "keyevent", "4"); await sleep(400);
// 我的监控: refresh button, the iOS empty state.
await tab(3);
check(await until(`document.querySelector(".android-refresh") && document.querySelector(".android-monitor-empty")?.textContent.includes("does not mine")`), "my monitors: refresh button and the iOS empty state");
await tab(4); await sleep(400);

// 应用图标: the launcher entry switches to the P icon and back, and the app keeps running.
await row("App Icon");
check(await until(`document.querySelectorAll(".android-icon").length === 3`), "three app icons, as on iOS");
check(launcherAliases().join() === "LauncherPearl", `launcher shows the pearl by default (${launcherAliases().join()})`);
await js(`document.querySelectorAll(".android-icon")[1].click()`); await sleep(2500);
check(launcherAliases().join() === "LauncherModern" && foreground() && await js(`document.querySelector(".android-icon.selected img")?.getAttribute("src").includes("modern")`), `P icon applied, app still running (${launcherAliases().join()})`);
shot("16-icon");
await js(`document.querySelectorAll(".android-icon")[0].click()`); await sleep(2500);
check(launcherAliases().join() === "LauncherPearl" && foreground(), "pearl icon restored");
adb("shell", "input", "keyevent", "4"); await sleep(400);

// 钱包与安全 opens the wallet's own panels; Pearl 网络 switches the wallet's network.
await row("Wallet & Security");
check(await until(`[...document.querySelectorAll(".android-row-label")].some((e) => e.textContent.includes("View Recovery Phrase"))`), "wallet & security page lists the wallet actions");
await row("Manage Wallets");
// 管理钱包 as on iOS (10-29): a row per wallet, ••• (or a long press) for 重命名 / 删除.
check(await until(`!document.querySelector(".wallet-frame").hidden && W.querySelector("dialog[open] .manage-list .manage-row button")`), "Manage Wallets opens the wallet's list, each wallet with rename / delete");
adb("shell", "input", "keyevent", "4"); await sleep(500);
await tab(4); await sleep(400);
check(await js(`document.querySelector(".android-page-title")?.textContent === "Wallet & Security"`), "the settings tab keeps its page, as iOS keeps its stack");
adb("shell", "input", "keyevent", "4"); await sleep(400);
await row("Pearl Network");
await until(`document.querySelector('select[aria-label="Pearl Network"]') && !document.querySelector('select[aria-label="Pearl Network"]').disabled`);
const setSelect = (selector, value) => js(`(() => { const s = document.querySelector(${JSON.stringify(selector)}); Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, "value").set.call(s, ${JSON.stringify(value)}); s.dispatchEvent(new Event("change", { bubbles: true })); return true; })()`);
await setSelect('select[aria-label="Pearl Network"]', "testnet");
check(await until(`W.querySelector(".network-badge.testnet")`, 20000), "Pearl Network switches the wallet to testnet");
await setSelect('select[aria-label="Pearl Network"]', "mainnet");
check(await until(`W.querySelector(".network-badge") && !W.querySelector(".network-badge.testnet")`, 20000), "and back to mainnet");
adb("shell", "input", "keyevent", "4"); await sleep(400);

// 锁屏盯盘's notification (Pro in the app; driven through the bridge here).
const now = Math.floor(Date.now() / 1000);
let r = await bridge("live.start", { title: "PRL live price", label1h: "1h", label24h: "24h", style: "full", state: { usd: 1.2345, change1h: -1.5, change24h: 4.25, at: now } });
check(r.ok && r.result.running, `live.start (${JSON.stringify(r.result || r.error)})`);
await sleep(800);
let record = notification();
check(/\$1\.23/.test(record) && /\+4\.25%/.test(record) && /-1\.50%/.test(record), "ticker notification shows the price and both changes (iOS formats)");
check(/ONGOING/.test(record) || /flags=0x[0-9a-f]*[2367abef]\b/i.test(record), "ticker notification is ongoing");
console.log(`  promoted (Live Update): ${/promot/i.test(record) ? record.match(/[^\n]*promot[^\n]*/i)[0].trim() : "not reported by dumpsys"}`);
r = await bridge("live.update", { state: { usd: 1.5, change1h: 0.1, change24h: 5, at: now + 60 } });
await sleep(800);
check(r.ok && /\$1\.50/.test(notification()), "live.update redraws the price");
r = await bridge("live.update", { state: { usd: 9, change1h: 0, change24h: 0, at: now - 600 } });
await sleep(500);
check(/\$1\.50/.test(notification()), "an older price does not replace a newer one");
await bridge("live.start", { title: "PRL live price", label1h: "1h", label24h: "24h", style: "island", state: { usd: 1.5, change1h: 0.1, change24h: 5, at: now + 61 } });
await sleep(800);
check(/PRL \$1\.50 {2}· {2}24h \+5\.00%/.test(notification()), "status-bar-first style: one line on the lock screen");
r = await bridge("live.status");
check(r.ok && r.result.running && r.result.style === "island", "live.status");
shot("17-ticker");
await bridge("live.stop"); await sleep(600);
check(!notification(), "live.stop removes the ticker");

// The review gate (fewer than 3 launches over 3 days), the clipboard clean-up after a pasted phrase.
r = await bridge("review.request");
check(r.ok && r.result.asked === false, "review prompt held back by the iOS gates on a fresh install");
await bridge("clipboard.write", { text: "alpha beta gamma" });
r = await bridge("clipboard.clear", { text: "alpha beta gamma" });
check(r.ok && r.result.cleared && (await bridge("clipboard.read")).result.text === "", "clipboard.clear empties a clipboard still holding the phrase");

// 恢复钱包 (the switcher's 添加钱包 → 用助记词恢复钱包, 10-29): word count, Paste, no screenshots once a phrase is in.
await tab(0);
await js(`W.querySelector("#wallet-switcher").click()`);
await until(`W.querySelector(".switcher-menu")`);
await click("W", ".switcher-menu [role=menuitem]", "Add Wallet");
await until(`W.querySelector("dialog[open] .add-wallet")`);
await click("W", "dialog[open] button", "Restore Wallet from Recovery Phrase");
check(await until(`W.querySelector("dialog[open] .flow-paste") && W.querySelector("dialog[open] textarea.phrase-input")`), "restore shows the word count and Paste");
await js(`(() => { const t = W.querySelector("dialog[open] textarea"); Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, "value").set.call(t, ${JSON.stringify(words.join(" "))}); t.dispatchEvent(new Event("input", { bubbles: true })); return true; })()`);
await sleep(700);
check(await js(`/\\b12\\b/.test(W.querySelector("dialog[open] textarea.phrase-input").nextElementSibling?.textContent || "") && !W.querySelector("dialog[open] form button.primary").disabled`), "12 words counted, Save enabled");
check(blank(shot("18-restore-secure")), "FLAG_SECURE while a phrase is typed into restore");
adb("shell", "input", "keyevent", "4"); await sleep(500);

// Dark theme and Chinese, applied to the page and the wallet.
await tab(4); await until(`document.querySelector(".android-settings")`);
await row("Theme & Colors");
await until(`document.querySelector(".android-page-title")`);
await js(`(() => { const s = document.querySelector('.android-settings select'); Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, "value").set.call(s, "dark"); s.dispatchEvent(new Event("change", { bubbles: true })); return true; })()`);
await sleep(500);
check(await js(`document.documentElement.dataset.theme === "dark" && document.querySelector(".wallet-frame").contentDocument.documentElement.dataset.theme === "dark"`), "dark theme in page and wallet");
shot("10-settings-dark");
adb("shell", "input", "keyevent", "4"); await sleep(400);
await js(`(() => { const s = document.querySelector('.android-settings select[aria-label="Language"]'); Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, "value").set.call(s, "zh-Hans"); s.dispatchEvent(new Event("change", { bubbles: true })); return true; })()`);
await sleep(500);
check((await js(`[...document.querySelectorAll(".sidebar nav .nav-label-full")].map((e) => e.textContent.trim()).join("|")`)) === "钱包|交易|挖矿监控|我的监控|设置", "Chinese tab bar");
await tab(0); await sleep(800); shot("11-wallet-dark-zh");

// Sign-in sheet: Turnstile loads inside the app.
await tab(4); await until(`document.querySelector(".android-settings")`);
await js(`document.querySelector(".android-row").click()`);
// First the choice for this device's settings (10-29, iOS WebLinkView), then the email form.
check(await until(`document.querySelector("dialog[open] .account-login-choice button.primary")`), "sign-in asks first whether to keep this device's settings");
await js(`document.querySelector("dialog[open] .account-login-choice button.primary").click()`);
check(await until(`document.querySelector("dialog[open] input[type=email]")`), "email sign-in sheet");
await js(`(async () => {
  const status = await (await fetch("/v1/web/status")).json();
  await new Promise((resolve, reject) => { const el = document.createElement("script"); el.src = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit"; el.onload = resolve; el.onerror = reject; document.head.append(el); });
  const box = document.createElement("div"); box.id = "qa-turnstile"; document.querySelector("dialog[open]").append(box);
  window.__qaProof = "";
  window.turnstile.render(box, { sitekey: status.auth.site_key, action: "email_login", callback: (token) => { window.__qaProof = "token " + token.length; }, "error-callback": (e) => { window.__qaProof = "error " + e; } });
  box.scrollIntoView();
  return "rendered";
})()`);
// An emulator usually gets the interactive checkbox; tap it like a person would.
let proof = "";
for (let i = 0; i < 30 && !proof; i++) {
  await sleep(1500);
  proof = await js(`window.__qaProof`);
  // Tap once the widget has had time to draw; again later if the first tap came before it was ready.
  if (!proof && (i === 4 || i === 12 || i === 20)) {
    // The checkbox sits inside Cloudflare's frame, invisible to uiautomator. A synthetic CDP click no longer
    // passes Cloudflare (2026-10-16), so tap the screen where the frame is: page pixels times the device
    // pixel ratio, below the status bar the shell pads the WebView by.
    const box = await js(`(() => { const b = document.getElementById("qa-turnstile")?.getBoundingClientRect(); return b && b.height > 0 && { x: b.left + 28, y: b.top + b.height / 2, dpr: devicePixelRatio }; })()`);
    const top = Number(adb("shell", "dumpsys", "window").match(/statusBars[^\n]*?frame=\[0,0\]\[\d+,(\d+)\]/)?.[1] || 0);
    if (box) adb("shell", "input", "tap", String(Math.round(box.x * box.dpr)), String(Math.round(box.y * box.dpr) + top));
  }
}
check(/^token \d+/.test(proof), `Turnstile verifies inside the WebView (${proof}); no email is sent`);
await sleep(2500); shot("12-sign-in");
adb("shell", "input", "keyevent", "4"); await sleep(500);

// Relaunch: the wallet is kept, locked, and opens with the screen lock.
adb("shell", "am", "force-stop", PKG);
adb("shell", "am", "start", "-W", "-n", `${PKG}/.MainActivity`);
await connect();
check(await until(`W?.querySelector(".welcome.locked")`, 30000), "after a restart the wallet exists and is locked");
await click("W", "form button.primary");
check(await enterPin(), "unlock prompt after restart");
check(await until(`W.querySelector(".balance-card")`, 20000), "unlocked after restart");
// The app is in Chinese since the language check above.
await click("W", ".balance-actions button", "收款") || await click("W", ".balance-actions button", "Receive");
await until(`W.querySelector("dialog[open] .receive code")`);
check(await js(`W.querySelector("dialog[open] .receive code").textContent`) === address, "same wallet after restart");
adb("shell", "input", "keyevent", "4"); await sleep(500);

// 锁定竖屏, on by default as on iPhone: a landscape turn keeps the app upright…
await tab(2); await sleep(800);
adb("shell", "settings", "put", "system", "accelerometer_rotation", "0"); adb("shell", "settings", "put", "system", "user_rotation", "1");
await sleep(2500);
check(await portrait(), "portrait lock holds against a landscape turn");
adb("shell", "settings", "put", "system", "user_rotation", "0"); await sleep(1200);
// …and with it off, rotation keeps the page.
await tab(4); await until(`document.querySelector(".android-settings")`);
await row("主题与配色");
await until(`document.querySelector(".settings-toggle input.switch")`);
await js(`document.querySelector(".settings-toggle input.switch").click()`); await sleep(600);
await tab(2); await sleep(800);
adb("shell", "settings", "put", "system", "user_rotation", "1");
await sleep(2500);
check(!await portrait() && await js(`!!document.querySelector(".android-segments") && document.querySelector(".nav-item.active") !== null`), "with the lock off, rotation turns the page and keeps the open tab");
shot("13-landscape");
adb("shell", "settings", "put", "system", "user_rotation", "0"); await sleep(1500);

check(logs.length === 0, `no page errors${logs.length ? ": " + [...new Set(logs)].slice(0, 8).join(" / ") : ""}`);
ws.close();
console.log(failures.length ? `\n${failures.length} check(s) failed` : "\nAll device checks passed");
process.exit(failures.length ? 1 : 0);
