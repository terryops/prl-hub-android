// The 交易 tab on an emulator or phone against the real safetrade.com: SafeTrade's public market data through
// the shell's safetrade.request (its Cloudflare firewall must accept the shell's HTTPS client), a signed
// request with a made-up key (refused by the API itself, not the firewall), the key page, the withdrawal's
// screen-lock prompt (the shell asks before sending) and the back button. No real key is used.
//   node qa/trade.mjs [--serial emulator-5554] [--apk path] [--pin 1111]
// Needs a debug build. Wipes the app's data (wallet, saved key, settings) and sets the device PIN when none is
// set, so it refuses anything but an emulator unless --wipe-real-device is given. Screenshots: out/trade-qa/.
import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { fileURLToPath } from "node:url";

const arg = (name, fallback) => process.argv.includes(name) ? process.argv[process.argv.indexOf(name) + 1] : fallback;
const ADB = `${process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`}/platform-tools/adb`;
const serial = arg("--serial", "emulator-5554"), pin = arg("--pin", "1111");
const apk = arg("--apk", fileURLToPath(new URL("../app/build/outputs/apk/play/debug/app-play-debug.apk", import.meta.url)));
const out = fileURLToPath(new URL("../out/trade-qa/", import.meta.url));
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
const emulator = ["ro.kernel.qemu", "ro.boot.qemu"].some((p) => { try { return adb("shell", "getprop", p).trim() === "1"; } catch { return false; } });
if (!emulator && !process.argv.includes("--wipe-real-device")) {
  console.error(`${serial} is not an emulator: this script wipes ${PKG}'s data (wallet included) and may set the screen-lock PIN. Pass --wipe-real-device to run it anyway.`);
  process.exit(2);
}
process.on("exit", () => { try { execFileSync(ADB, ["-s", serial, "forward", "--remove", "tcp:9334"], { stdio: "ignore" }); } catch { /* Not set. */ } });
const proxy = adb("shell", "settings", "get", "global", "http_proxy").trim().match(/^127\.0\.0\.1:(\d+)$/);
if (proxy) adb("reverse", `tcp:${proxy[1]}`, `tcp:${proxy[1]}`);
adb("install", "-r", apk);
adb("shell", "pm", "clear", PKG);
try { execFileSync(ADB, ["-s", serial, "shell", "locksettings", "set-pin", pin], { stdio: "ignore" }); } catch { /* already set */ }
adb("shell", "input", "keyevent", "224"); adb("shell", "wm", "dismiss-keyguard"); await sleep(800);
for (let i = 0; i < 3 && /mKeyguardShowing=true/.test(adb("shell", "dumpsys", "activity", "activities")); i++) {
  adb("shell", "input", "keyevent", "224"); await sleep(400);
  adb("shell", "input", "swipe", "540", "1900", "540", "500", "250"); await sleep(1000);
  adb("shell", "input", "text", pin); adb("shell", "input", "keyevent", "66"); await sleep(1500);
}
adb("shell", "am", "start", "-W", "-n", `${PKG}/.MainActivity`);
await connect();
const pageShot = async (name) => { const r = await send("Page.captureScreenshot", { format: "png" }); writeFileSync(`${out}${name}.png`, Buffer.from(r.result.data, "base64")); };
const text = (selector) => js(`[...document.querySelectorAll(${JSON.stringify(selector)})].map((e) => e.textContent.trim()).join(" | ")`);

check(await until(`document.querySelectorAll(".sidebar nav .nav-item").length === 5`), "five tabs");
await tab(1);
check(await until(`document.querySelector(".trade-tab")`), "交易 tab opens");
// Public market data, from safetrade.com through the shell.
check(await until(`/^\\d+\\.\\d+$/.test(document.querySelector(".trade-price strong")?.textContent || "")`, 30000), `live price: ${await text(".trade-price strong")}`);
check(await until(`document.querySelector(".trade-kline .kline-plot")`, 30000), "live K-line drawn");
check(await until(`document.querySelectorAll(".trade-ticker-cell span")[0]?.textContent !== "—"`), `ticker grid: ${await text(".trade-ticker-cell")}`);
const trace = await bridge("safetrade.request", { method: "GET", path: "/cdn-cgi/trace" });
check(trace.ok && trace.result.status === 200 && /\nip=/.test(trace.result.body), `Cloudflare trace through the shell: ${trace.ok ? trace.result.status : trace.error}`);
const depth = await bridge("safetrade.request", { method: "GET", path: "/api/v2/trade/public/markets/pearlusdt/depth", query: { limit: "5" } });
check(depth.ok && depth.result.status === 200 && /"asks"/.test(depth.result.body), `order book: ${depth.ok ? depth.result.status : depth.error}`);
const refused = await bridge("safetrade.request", { method: "GET", path: "/api/v2/trade/account/balances/spot", auth: "stored" });
check(!refused.ok && refused.error === "no_credentials", `no key: account calls refused by the shell (${refused.error})`);
const outside = await bridge("safetrade.request", { method: "GET", path: "/api/v2/trade/account/members/me", auth: "stored" });
check(!outside.ok && outside.error === "bad_params", "paths outside the whitelist refused");
await sleep(1500);
await pageShot("1-trade-nokey");
shot("1-trade-nokey-screen");

// A made-up key: the API itself refuses it in JSON (the firewall would answer HTML). SafeTrade says 404
// record.not_found for a key it doesn't know (2026-10-28); the page reads that as a wrong key.
const fake = await bridge("safetrade.request", { method: "GET", path: "/api/v2/trade/account/balances/spot", auth: "explicit", key: "qa0000000000fake", secret: "qa0000000000000000fakesecret" });
const refusedKey = (r) => r.ok && ([401, 403].includes(r.result.status) || (r.result.status === 404 && /record\.not_found/.test(r.result.body)));
check(refusedKey(fake), `signed request answered by the API: ${fake.ok ? fake.result.status + " " + fake.result.body.slice(0, 120) : fake.error}`);
check(fake.ok && !/<html/i.test(fake.result.body), "not the firewall's HTML page");

// The key page: save the made-up key, which is checked first and saved with a warning.
await click("document", ".trade-nokey button.primary");
check(await until(`document.querySelector('input[aria-label="SafeTrade API Key"]')`), "key page opens from the card");
await js(`(() => { const set = (sel, v) => { const el = document.querySelector(sel); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set.call(el, v); el.dispatchEvent(new Event("input", { bubbles: true })); }; set('input[aria-label="SafeTrade API Key"]', "qa0000000000fake"); set('input[aria-label="SafeTrade API Secret"]', "qa0000000000000000fakesecret"); })()`);
await click("document", ".trade-keys-body button.primary");
check(await until(`document.querySelector(".trade-keys-message")`, 30000), `save result: ${await text(".trade-keys-message")}`);
check(await js(`document.querySelector(".trade-keys .android-row-value")?.textContent`) === "qa00••••ke", "masked key shown after saving");
check(!(await js(`document.documentElement.innerHTML.includes("fakesecret")`)), "secret not in the page");
await pageShot("2-keys");
adb("shell", "input", "keyevent", "4");
check(await until(`document.querySelector(".trade-balances") && !document.querySelector(".trade-sub")`), "system back leaves the key page");
check(await until(`/API/.test(document.querySelector(".trade-error")?.textContent || "") && !/HTTP 404/.test(document.querySelector(".trade-error").textContent)`, 30000), `wrong key explained: ${await text(".trade-error")}`);
await pageShot("3-trade-badkey");

// The withdrawal: the shell asks for the screen lock before it sends anything.
const asked = bridge("safetrade.request", { method: "POST", path: "/api/v2/trade/account/withdraws", auth: "stored", title: "QA withdrawal check", json: '{"currency":"pearl","blockchain_key":"pearl-tokens","address":"prl1qa","amount":0.0005}' });
check(!!(await waitUi(/QA withdrawal check/, 15000)), "screen-lock prompt before the withdrawal");
shot("4-withdraw-prompt");
adb("shell", "input", "keyevent", "4");
const cancelled = await asked;
check(!cancelled.ok && /cancel/.test(cancelled.error), `cancelled prompt sends nothing: ${cancelled.error}`);
const again = bridge("safetrade.request", { method: "POST", path: "/api/v2/trade/account/withdraws", auth: "stored", title: "QA withdrawal check", json: '{"currency":"pearl","blockchain_key":"pearl-tokens","address":"prl1qa","amount":0.0005}' });
check(await enterPin(), "PIN entered");
const sent = await again;
check(refusedKey(sent), `confirmed withdrawal reaches SafeTrade (made-up key refused): ${sent.ok ? sent.result.status + " " + sent.result.body.slice(0, 100) : sent.error}`);

// Clear the key again from 设置 → 交易（SafeTrade）.
await tab(4);
check(await row(await js(`[...document.querySelectorAll(".android-row-label")].map((e) => e.firstChild?.textContent.trim()).find((t) => /SafeTrade/.test(t))`)), "settings row opens 交易（SafeTrade）");
check(await until(`document.querySelector(".trade-keys .android-row.danger")`, 15000), "settings page shows the saved key");
await pageShot("5-settings");
await click("document", ".trade-keys .android-row.danger");
check(await until(`document.querySelector('input[aria-label="SafeTrade API Key"]')`), "key cleared");
const keys = await bridge("safetrade.keys");
check(keys.ok && keys.result.set === false, "shell reports no key");

check(logs.length === 0, `no page errors${logs.length ? ": " + logs.join(" | ") : ""}`);
console.log(failures.length ? `\n${failures.length} failed` : "\nall passed");
process.exit(failures.length ? 1 : 0);
