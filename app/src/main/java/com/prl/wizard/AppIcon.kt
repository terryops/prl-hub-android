package com.prl.wizard

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 设置 → 外观 → 应用图标, as in the iOS app (prl-hub `Sources/App/AppIcon.swift`): the blue pearl
 * (default), the P icon and the classic miner pearl. Each is a launcher `activity-alias` of
 * MainActivity with its own icon; exactly one is enabled, and that one is the choice. Per device,
 * not synced: it is this device's home screen. The launcher redraws the icon itself, so unlike
 * iOS there is no system alert; a launcher may move a pinned icon back to the app drawer.
 */
object AppIcon {
    enum class Choice(val id: String, val alias: String, val defaultEnabled: Boolean) {
        PEARL("pearl", "com.prl.wizard.LauncherPearl", true),
        MODERN("modern", "com.prl.wizard.LauncherModern", false),
        CLASSIC("classic", "com.prl.wizard.LauncherClassic", false);

        companion object {
            fun of(id: String): Choice? = entries.firstOrNull { it.id == id }
        }
    }

    private fun component(context: Context, choice: Choice) = ComponentName(context.packageName, choice.alias)

    private fun enabled(context: Context, choice: Choice): Boolean =
        when (context.packageManager.getComponentEnabledSetting(component(context, choice))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> choice.defaultEnabled
            else -> false
        }

    fun current(context: Context): Choice = Choice.entries.firstOrNull { enabled(context, it) } ?: Choice.PEARL

    /** Enables `choice`'s alias and disables the others, keeping the app running. */
    fun apply(context: Context, choice: Choice) {
        if (current(context) == choice && Choice.entries.count { enabled(context, it) } == 1) return
        val pm = context.packageManager
        fun state(c: Choice) = if (c == choice) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (Build.VERSION.SDK_INT >= 33) {
            pm.setComponentEnabledSettings(
                Choice.entries.map { PackageManager.ComponentEnabledSetting(component(context, it), state(it), PackageManager.DONT_KILL_APP) },
            )
        } else {
            // The new alias first, so the launcher never sees the app without an entry.
            pm.setComponentEnabledSetting(component(context, choice), state(choice), PackageManager.DONT_KILL_APP)
            Choice.entries.filter { it != choice }.forEach {
                pm.setComponentEnabledSetting(component(context, it), state(it), PackageManager.DONT_KILL_APP)
            }
        }
    }
}
