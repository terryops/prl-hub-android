package com.prl.wizard

/** An FCM data message (BRIDGE.md "Push payload") reduced to what the notification shows. */
data class PushPayload(
    val title: String,
    val body: String,
    val channel: String,
    val tag: String,
    val route: String?,
    /**
     * The group, as iOS's `thread-id`. Offline alerts stack in "worker-offline" (no collapse id):
     * the first alert, the still-offline reminder and the all-clear stay in the latest 20 entries.
     * 到账提醒 (payments) stack in "income" the same way: each push names its own transaction and
     * replaces nothing. Price and test alerts sit in "price-alert" and keep replacing the rule's last
     * one (the tag, as iOS's `apns-collapse-id`). Grouped, they also keep Android from bundling them
     * with the 锁屏盯盘 notification, which it does to an app's ungrouped notifications.
     */
    val group: String,
) {
    companion object {
        const val CHANNEL_PRICE = "price"
        const val CHANNEL_OFFLINE = "offline"
        const val CHANNEL_INCOME = "income"
        const val KIND_LIVE = "live"
        const val GROUP_OFFLINE = "worker-offline"
        const val GROUP_PRICE = "price-alert"
        const val GROUP_INCOME = "income"

        fun from(data: Map<String, String>): PushPayload? {
            val title = data["title"].orEmpty().trim()
            val body = data["body"].orEmpty().trim()
            if (title.isEmpty() && body.isEmpty()) return null
            val kind = data["kind"].orEmpty()
            if (kind == KIND_LIVE) return null
            return PushPayload(
                title = title,
                body = body,
                channel = when (kind) { "offline" -> CHANNEL_OFFLINE; "income" -> CHANNEL_INCOME; else -> CHANNEL_PRICE },
                tag = data["tag"]?.trim()?.takeIf { it.isNotEmpty() }?.take(64) ?: kind.ifEmpty { "pearl" },
                // A payment opens the wallet (the Worker sends route "wallet"; iOS PushRoute does the same).
                route = data["route"]?.takeIf(WebRoutes::isRoute) ?: if (kind == "income") "wallet" else null,
                group = when (kind) { "offline" -> GROUP_OFFLINE; "income" -> GROUP_INCOME; else -> GROUP_PRICE },
            )
        }
    }
}
