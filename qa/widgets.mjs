// Inflates real size-mapped RemoteViews on an Android 12+ device, checks resizing, empty/stale
// data, standalone periods and all 16 locales, and saves renders for visual review.
// Build first: ./gradlew assemblePlayDebug assemblePlayDebugAndroidTest
// Does not clear app data or place widgets on the user's launcher.
import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { fileURLToPath } from "node:url";

const arg = (key, fallback) => process.argv.includes(key) ? process.argv[process.argv.indexOf(key) + 1] : fallback;
const adbPath = `${process.env.ANDROID_HOME || `${homedir()}/Library/Android/sdk`}/platform-tools/adb`;
const serial = arg("--serial", "emulator-5554");
const adb = (...args) => execFileSync(adbPath, ["-s", serial, ...args], { encoding: "utf8", maxBuffer: 64 << 20 });
const path = (relative) => fileURLToPath(new URL(relative, import.meta.url));
const out = path("../out/widget-qa/");
mkdirSync(out, { recursive: true });
console.log(adb("install", "-r", arg("--apk", path("../app/build/outputs/apk/play/debug/app-play-debug.apk"))).trim());
console.log(adb("install", "-r", arg("--test-apk", path("../app/build/outputs/apk/androidTest/play/debug/app-play-debug-androidTest.apk"))).trim());
const result = adb("shell", "am", "instrument", "-w", "com.prl.wizard.test/com.prl.wizard.WidgetRenderCheck");
writeFileSync(`${out}result.txt`, result);
console.log(result.trim());
if (!result.includes("widget_checks_passed:")) process.exit(1);
const names = adb("shell", "run-as", "com.prl.wizard", "ls", "files/widget-qa").trim().split(/\s+/).filter((name) => /^[\w-]+\.png$/.test(name));
for (const name of names) {
  const png = execFileSync(adbPath, ["-s", serial, "exec-out", "run-as", "com.prl.wizard", "cat", `files/widget-qa/${name}`], { maxBuffer: 64 << 20 });
  writeFileSync(`${out}${name}`, png);
}
console.log(`Saved ${names.length} renders to ${out}`);
