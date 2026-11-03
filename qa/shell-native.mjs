// Renderer, dialog, popup and bridge executor regressions. Use a fresh disposable API 29+ install.
// Build: ./gradlew assemblePlayDebug assemblePlayDebugAndroidTest -PcheckRunner=com.prl.wizard.ShellLifecycleCheck
// Run: node qa/shell-native.mjs [--serial emulator-5554] [--package com.prl.wizard] [--apk path] [--test-apk path]
import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { fileURLToPath } from "node:url";

const arg = (key, fallback) => process.argv.includes(key) ? process.argv[process.argv.indexOf(key) + 1] : fallback;
const adbPath = `${process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`}/platform-tools/adb`;
const serial = arg("--serial", "emulator-5554"), pkg = arg("--package", "com.prl.wizard");
const adb = (...args) => execFileSync(adbPath, ["-s", serial, ...args], { encoding: "utf8", maxBuffer: 4 << 20 });
const path = (relative) => fileURLToPath(new URL(relative, import.meta.url));
const out = path("../out/shell-native-qa/");
mkdirSync(out, { recursive: true });
console.log(adb("install", "-r", arg("--apk", path("../app/build/outputs/apk/play/debug/app-play-debug.apk"))).trim());
console.log(adb("install", "-r", arg("--test-apk", path("../app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk"))).trim());
const result = adb("shell", "am", "instrument", "-w", `${pkg}.test/com.prl.wizard.ShellLifecycleCheck`);
writeFileSync(`${out}result.txt`, result);
console.log(result.trim());
if (!result.includes("shell_checks_passed:")) process.exit(1);
