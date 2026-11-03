// Real WorkManager queue regressions; requires an unlocked device and a fresh disposable install.
// Refuses an already-installed target/test package and uninstalls only the new audit installation.
// Build: ./gradlew assemblePlayDebug assemblePlayDebugAndroidTest -PcheckRunner=com.prl.wizard.WidgetScheduleCheck
// Run: node qa/widget-refresh.mjs [--serial emulator-5554] [--package com.prl.wizard] [--apk path] [--test-apk path]
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, readdirSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const arg = (key, fallback) => process.argv.includes(key) ? process.argv[process.argv.indexOf(key) + 1] : fallback;
const sdk = process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`;
const adbPath = join(sdk, "platform-tools", "adb");
const serial = arg("--serial", "emulator-5554"), pkg = arg("--package", "com.prl.wizard");
const adb = (...args) => execFileSync(adbPath, ["-s", serial, ...args], { encoding: "utf8", maxBuffer: 4 << 20 });
const path = (relative) => fileURLToPath(new URL(relative, import.meta.url));
const apk = arg("--apk", path("../app/build/outputs/apk/play/debug/app-play-debug.apk"));
const testApk = arg("--test-apk", path("../app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk"));
const aapt = readdirSync(join(sdk, "build-tools"))
  .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }))
  .map((version) => join(sdk, "build-tools", version, "aapt")).find(existsSync);
if (!aapt) throw new Error("Android SDK aapt is required to verify the disposable APK package before installing.");
const apkPackage = (file) => execFileSync(aapt, ["dump", "badging", file], { encoding: "utf8", maxBuffer: 4 << 20 }).match(/^package: name='([^']+)'/m)?.[1];
if (apkPackage(apk) !== pkg || apkPackage(testApk) !== `${pkg}.test`) throw new Error("--package must match both supplied APKs; nothing was installed.");
const installed = new Set(adb("shell", "pm", "list", "packages").trim().split(/\s+/));
for (const name of [pkg, `${pkg}.test`]) {
  if (installed.has(`package:${name}`)) throw new Error(`${name} is already installed. Use a fresh disposable package/device; existing app data is never cleared.`);
}

const out = path("../out/widget-refresh-qa/");
mkdirSync(out, { recursive: true });
try {
  console.log(adb("install", apk).trim());
  console.log(adb("install", testApk).trim());
  const result = adb("shell", "am", "instrument", "-w", "-e", "package", pkg, `${pkg}.test/com.prl.wizard.WidgetScheduleCheck`);
  writeFileSync(`${out}result.txt`, result);
  console.log(result.trim());
  if (!result.includes("widget_schedule_checks_passed: 2")) throw new Error("Widget refresh scheduling checks failed; see out/widget-refresh-qa/result.txt.");
} finally {
  for (const name of [`${pkg}.test`, pkg]) {
    try { console.log(adb("uninstall", name).trim()); }
    catch (error) { console.error(`Disposable audit package cleanup failed: ${name}: ${error.message}`); process.exitCode = 1; }
  }
}
