package com.prl.wizard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushPayloadTest {
    @Test
    fun offlineGoesToTheOfflineChannel() {
        val p = PushPayload.from(mapOf("title" to "Worker offline", "body" to "rig-3", "kind" to "offline", "tag" to "w3", "route" to "workers"))!!
        assertEquals(PushPayload.CHANNEL_OFFLINE, p.channel)
        assertEquals("w3", p.tag)
        assertEquals("workers", p.route)
        // Stacked in one group like iOS's "worker-offline" thread, not replacing each other.
        assertEquals(PushPayload.GROUP_OFFLINE, p.group)
    }

    @Test
    fun priceAlertsShareTheirOwnGroupAndReplaceByRule() {
        // iOS's "price-alert" thread; the rule id stays the tag, so a rule's next alert replaces its last.
        val p = PushPayload.from(mapOf("body" to "PRL 0.4", "kind" to "price", "tag" to "rule-1"))!!
        assertEquals(PushPayload.GROUP_PRICE, p.group)
        assertEquals("rule-1", p.tag)
        assertEquals(PushPayload.GROUP_PRICE, PushPayload.from(mapOf("body" to "hi", "kind" to "test"))!!.group)
        assertEquals(PushPayload.GROUP_PRICE, PushPayload.from(mapOf("body" to "hi"))!!.group)
    }

    @Test
    fun paymentsStackInTheirOwnGroupAndOpenTheWallet() {
        // The Worker's 到账提醒 (fcm.js dataFor: kind income, tag income:<txid tail>, route wallet).
        val p = PushPayload.from(mapOf("title" to "Main", "body" to "+12.5 PRL", "kind" to "income", "tag" to "income:0123456789abcdef", "route" to "wallet"))!!
        assertEquals(PushPayload.CHANNEL_INCOME, p.channel)
        assertEquals(PushPayload.GROUP_INCOME, p.group)
        assertEquals("wallet", p.route)
        assertEquals("income:0123456789abcdef", p.tag)
        // Without a route (an older Worker) a payment still opens the wallet.
        assertEquals("wallet", PushPayload.from(mapOf("body" to "+1 PRL", "kind" to "income"))!!.route)
    }

    @Test
    fun priceAndTestUseThePriceChannel() {
        assertEquals(PushPayload.CHANNEL_PRICE, PushPayload.from(mapOf("body" to "PRL 0.4", "kind" to "price"))!!.channel)
        assertEquals(PushPayload.CHANNEL_PRICE, PushPayload.from(mapOf("body" to "hi", "kind" to "test"))!!.channel)
        assertEquals(PushPayload.CHANNEL_PRICE, PushPayload.from(mapOf("body" to "hi"))!!.channel)
    }

    @Test
    fun tagFallsBackToKindAndBadRoutesAreDropped() {
        val p = PushPayload.from(mapOf("title" to "t", "kind" to "price", "route" to "../x"))!!
        assertEquals("price", p.tag)
        assertNull(p.route)
        assertEquals("pearl", PushPayload.from(mapOf("title" to "t"))!!.tag)
    }

    @Test
    fun emptyMessagesAreIgnored() {
        assertNull(PushPayload.from(mapOf("kind" to "price")))
        assertNull(PushPayload.from(mapOf("title" to " ", "body" to "")))
    }
}
