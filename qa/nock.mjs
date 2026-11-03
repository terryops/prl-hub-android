// NOCK (Nockchain) in the Android app, on an emulator or phone: the wallet's WebAssembly core under the
// app's CSP, the public node rpc.nockbox.org fetched by the WebView, and the second node
// (216.158.95.10:5556, plain HTTP) through the shell's `nock.rpc`. Restores the public `abandon … about`
// test wallet and only reads from the nodes: nothing is signed for real or broadcast.
//   node qa/nock.mjs [--serial emulator-5554] [--apk path] [--pin 1111]
// Needs a debug build whose web bundle has the wallet's NOCK side. Wipes the app's data.
import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { fileURLToPath } from "node:url";

const arg = (name, fallback) => process.argv.includes(name) ? process.argv[process.argv.indexOf(name) + 1] : fallback;
const ADB = `${process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`}/platform-tools/adb`;
const serial = arg("--serial", "emulator-5554"), pin = arg("--pin", "1111");
const apk = arg("--apk", fileURLToPath(new URL("../app/build/outputs/apk/play/debug/app-play-debug.apk", import.meta.url)));
const out = fileURLToPath(new URL("../out/nock-qa/", import.meta.url));
mkdirSync(out, { recursive: true });
const PKG = "com.prl.wizard";
const ABANDON = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
const ADDRESS = "3zxKWhhd8HY9pT2ZCZNUSEz2XWwSk15P2sD3atnnnxRtHa2Ta1H4jRy";   // the official wallet's address for that seed
const RECIPIENT = "3b3eG8WgWHfXTeAMJzNDWxM3b6WFadxQiiJRQrY6zPJ4J2F5QtHjZRQ";
const adb = (...args) => execFileSync(ADB, ["-s", serial, ...args], { encoding: "utf8", maxBuffer: 64 << 20 });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const failures = [];
const check = (ok, label) => { console.log(`${ok ? "✓" : "✗"} ${label}`); if (!ok) failures.push(label); };

async function bootCompleted() { for (let i = 0; i < 240; i++) { try { if (adb("shell", "getprop", "sys.boot_completed").trim() === "1") return; } catch { /* booting */ } await sleep(1000); } throw new Error("device did not boot"); }
function dump() { adb("shell", "uiautomator", "dump", "/sdcard/ui.xml"); return adb("shell", "cat", "/sdcard/ui.xml"); }
async function waitUi(pattern, timeout = 15000) { for (const end = Date.now() + timeout; Date.now() < end; await sleep(500)) { const xml = dump(); if (pattern.test(xml)) return xml; } return null; }
async function enterPin() {
  if (!await waitUi(/com\.android\.systemui|PIN|password/i, 20000)) return false;
  await sleep(700); adb("shell", "input", "text", pin); adb("shell", "input", "keyevent", "66");
  return true;
}

// ---- CDP (raw: Playwright's connectOverCDP needs browser commands the WebView lacks)
let ws, seq = 0;
const pending = new Map(), logs = [], listeners = [];
async function connect() {
  let targets;
  for (let i = 0; i < 60; i++) {
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
    if (d.method === "Log.entryAdded" && d.params.entry.level === "error" && !/Failed to load resource/.test(d.params.entry.text) && !/challenges\.cloudflare\.com/.test(d.params.entry.url || "")) logs.push(`log: ${d.params.entry.text}`);
    for (const l of listeners) l(d);
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
  for (const end = Date.now() + timeout; Date.now() < end; await sleep(300)) { try { if (await js(`!!(${expression})`)) return true; } catch { /* busy */ } }
  return false;
}
const click = (selector, text = "") => js(`(() => { const el = [...W.querySelectorAll(${JSON.stringify(selector)})].find((e) => e.textContent.includes(${JSON.stringify(text)}) && !e.disabled); if (!el) return false; el.click(); return true; })()`);
// React-controlled fields: the native setter, then an input event.
const fill = (selector, value) => js(`(() => { const el = W.querySelector(${JSON.stringify(selector)}); if (!el) return false; Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), "value").set.call(el, ${JSON.stringify(value)}); el.dispatchEvent(new Event("input", { bubbles: true })); return true; })()`);
async function screenshot(name) {
  const r = await send("Page.captureScreenshot", { format: "png" });
  if (r.result?.data) writeFileSync(`${out}${name}.png`, Buffer.from(r.result.data, "base64"));
}

// ---- run
await bootCompleted();
// The emulator reaches the internet through the host's proxy (README "Emulator QA").
let proxy = adb("shell", "settings", "get", "global", "http_proxy").trim().match(/^127\.0\.0\.1:(\d+)$/);
if (!proxy && process.env.NOCK_QA_PROXY) { adb("shell", "settings", "put", "global", "http_proxy", `127.0.0.1:${process.env.NOCK_QA_PROXY}`); proxy = [0, process.env.NOCK_QA_PROXY]; }
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

// Restore the public test wallet (its seed is in every BIP39 test suite; it holds nothing of value).
check(await until(`W?.querySelector("button.primary")`, 30000), "wallet tab open");
await click("button", "Restore Wallet");   // the welcome screen, as iOS (10-29)
check(await until(`W.querySelector("dialog[open] textarea")`), "restore form");
await fill("dialog[open] textarea", ABANDON);
await click("dialog[open] form button.primary");
check(await enterPin(), "screen-lock prompt confirmed with the PIN");
check(await until(`W.querySelector(".balance-card")`, 30000), "wallet restored and unlocked");

// NOCK is opt-in (iOS 2.1.9): a wallet that never used it shows nothing NOCK until 设置 → 钱包与安全 → NOCK 钱包.
await sleep(2000);
check(await js(`!W.querySelector(".nock-link, .nock-strip")`), "NOCK off by default: no NOCK entry on the wallet page");
await js(`document.querySelectorAll(".sidebar nav .nav-item")[4].click()`);
await until(`document.querySelector(".android-settings")`);
await js(`(() => { const r = [...document.querySelectorAll(".android-row")].find((e) => e.querySelector(".android-row-label")?.firstChild?.textContent.trim() === "Wallet & Security"); r?.click(); return !!r; })()`);
check(await until(`document.querySelector(".android-row-switch input.switch") && !document.querySelector(".android-row-switch input.switch").checked`), "设置 → 钱包与安全: the NOCK 钱包 switch, off");
await js(`document.querySelector(".android-row-switch input.switch").click()`);
check(await until(`document.querySelector(".android-row-switch input.switch")?.checked`), "switched on");
await js(`window.__pearlBack()`);
await js(`document.querySelectorAll(".sidebar nav .nav-item")[0].click()`);
// The quiet link at the bottom, without a ×; nothing from Nockchain until it's opened.
check(await until(`W.querySelector(".nock-link .shortcut-open")`, 20000), "wallet page: NOCK link (not used yet)");
check(await js(`!W.querySelector(".nock-link .shortcut-close")`), "the NOCK line has no ×");
await click(".nock-link .shortcut-open");
check(await until(`W.querySelector(".nock-summary")`), "NOCK page opens");
// Loaded from the real nodes: a balance in place of the placeholder, no problem line.
const loaded = await until(`W.querySelector(".nock-summary .balance-number") && W.querySelector(".nock-summary .balance-number").textContent !== "——"`, 60000);
const problem = await js(`W.querySelector(".nock-problem")?.textContent || ""`);
check(loaded && !problem, `balance read from the public Nockchain nodes (${await js(`W.querySelector(".nock-summary .balance-number")?.textContent`)} NOCK${problem ? "; " + problem : ""})`);
check(await js(`!W.body.textContent.includes("NOCK wallet component is missing")`), "the WebAssembly core runs under the app's CSP");
await screenshot("01-nock-page");

// Receive: the address the official wallet shows for this seed, derived in the WebView.
await click(".nock-actions button", "Receive");
// The receive card (10-29): the address in 4-character groups on a copy button, described by the plain address.
check(await until(`(() => { const b = W.querySelector("dialog.nock-dialog[open] .nock-receive-address"); return !!b && b.textContent.replace(/\\s/g, "") === ${JSON.stringify(ADDRESS)} && W.getElementById(b.getAttribute("aria-describedby"))?.textContent === ${JSON.stringify(ADDRESS)}; })()`), "receive shows the official wallet's address for the seed");
await screenshot("02-receive");
// Android back closes the NOCK dialog first.
adb("shell", "input", "keyevent", "4"); await sleep(600);
check(await js(`!W.querySelector("dialog.nock-dialog[open]") && !!W.querySelector(".nock-summary")`), "back closes the NOCK dialog, stays on the NOCK page");

// The second node: with rpc.nockbox.org refused, a refresh must still load, through the shell's nock.rpc.
// Counted at the bridge (rpc.nockbox.org may already sit out its bench if it failed on the first read: its
// gateway answers 503 to some networks, which is why the second node exists).
const counting = await js(`(() => { const ch = document.querySelector(".wallet-frame").contentWindow.PearlAndroid; if (!ch) return false; const post = ch.postMessage.bind(ch); window.__nockRpc = 0; try { ch.postMessage = (m) => { try { if (JSON.parse(m).method === "nock.rpc") window.__nockRpc++; } catch {} return post(m); }; } catch { return false; } return ch.postMessage !== post; })()`);
let refused = 0;
listeners.push((d) => { if (d.method === "Fetch.requestPaused") { refused++; void send("Fetch.failRequest", { requestId: d.params.requestId, errorReason: "ConnectionRefused" }); } });
await send("Fetch.enable", { patterns: [{ urlPattern: "https://rpc.nockbox.org/*" }] });
await click(".nock-summary .balance-heading button");
await sleep(1000);
check(await until(`!W.querySelector(".nock-summary .balance-heading button").disabled`, 60000), "refresh finished");
const problem2 = await js(`W.querySelector(".nock-problem")?.textContent || ""`);
const viaShell = counting ? await js(`window.__nockRpc`) : null;
check((viaShell ?? refused) > 0 && !problem2, `rpc.nockbox.org refused ${refused}×; ${viaShell ?? "?"} nock.rpc calls to 216.158.95.10:5556 through the shell; read complete${problem2 ? " — but: " + problem2 : ""}`);
await send("Fetch.disable");

// Send: the form's checks and the core's answer (this wallet holds no spendable NOCK); nothing is broadcast.
await click(".nock-actions button", "Send");
check(await until(`W.querySelector("dialog.nock-dialog[open] textarea")`), "send form");
await fill("dialog.nock-dialog[open] textarea", RECIPIENT);
await fill('dialog.nock-dialog[open] input[inputmode="decimal"]', "1");
await until(`!W.querySelector("dialog.nock-dialog[open] button.primary").disabled`, 10000);
await click("dialog.nock-dialog[open] button.primary");
const answered = await until(`W.querySelector("dialog.nock-dialog[open] .field-note.error") || W.querySelector("dialog.nock-dialog[open] .receipt")`, 60000);
const answer = await js(`W.querySelector("dialog.nock-dialog[open] .field-note.error")?.textContent || (W.querySelector("dialog.nock-dialog[open] .receipt") ? "receipt" : "")`);
check(answered && /No spendable NOCK|Not enough balance|More than the available balance|receipt/.test(answer), `send answered: ${answer}`);
await screenshot("03-send");
adb("shell", "input", "keyevent", "4"); await sleep(600);
adb("shell", "input", "keyevent", "4"); await sleep(600);
check(await js(`!W.querySelector(".nock-summary") && !!W.querySelector(".balance-card")`), "back leaves the NOCK page for the PRL wallet");
check(await until(`W.querySelector(".nock-strip")`, 15000), "the wallet now uses NOCK: the strip replaces the link");
await screenshot("04-wallet-strip");

check(!logs.length, `no page errors${logs.length ? ": " + logs.join(" | ") : ""}`);
console.log(failures.length ? `\n${failures.length} check(s) failed` : "\nAll NOCK device checks passed");
ws.close();
process.exit(failures.length ? 1 : 0);
