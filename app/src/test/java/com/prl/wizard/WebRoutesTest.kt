package com.prl.wizard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebRoutesTest {
    @Test
    fun rootAndDirectoriesMapToIndex() {
        assertEquals("www/index.html", WebRoutes.assetPath("https://pearlhub.net/"))
        assertEquals("www/index.html", WebRoutes.assetPath("https://pearlhub.net"))
        assertEquals("www/index.html", WebRoutes.assetPath("https://pearlhub.net/?client=android&store=play"))
        assertEquals("www/index.html", WebRoutes.assetPath("https://pearlhub.net/?q=a|b{c}#workers"))
        assertEquals("www/wallet/index.html", WebRoutes.assetPath("https://pearlhub.net/wallet/"))
        assertEquals("www/index.html", WebRoutes.assetPath("https://PearlHub.NET:443/"))
    }

    @Test
    fun filesMapUnderWww() {
        assertEquals("www/privacy.html", WebRoutes.assetPath("https://pearlhub.net/privacy.html"))
        assertEquals("www/assets/index-3f9a.js", WebRoutes.assetPath("https://pearlhub.net/assets/index-3f9a.js?v=2"))
        assertEquals("www/fonts/a b.woff2", WebRoutes.assetPath("https://pearlhub.net/fonts/a%20b.woff2"))
        assertEquals("www/.well-known/x.json", WebRoutes.assetPath("https://pearlhub.net/.well-known/x.json"))
        assertEquals("www/文件.txt", WebRoutes.assetPath("https://pearlhub.net/%E6%96%87%E4%BB%B6.txt"))
    }

    @Test
    fun apiAndOtherHostsGoToTheNetwork() {
        assertNull(WebRoutes.assetPath("https://pearlhub.net/v1/price"))
        assertNull(WebRoutes.assetPath("https://pearlhub.net/v1/account/config"))
        assertNull(WebRoutes.assetPath("http://pearlhub.net/"))
        assertNull(WebRoutes.assetPath("https://www.pearlhub.net/"))
        assertNull(WebRoutes.assetPath("https://pearlhub.net.evil.com/"))
        assertNull(WebRoutes.assetPath("https://evil.com/pearlhub.net/index.html"))
        assertNull(WebRoutes.assetPath("https://user@pearlhub.net/"))
        assertNull(WebRoutes.assetPath("https://pearlhub.net:8443/"))
        assertNull(WebRoutes.assetPath("file:///android_asset/www/index.html"))
        assertNull(WebRoutes.assetPath("not a url"))
    }

    @Test
    fun traversalAndOddEncodingsAreRefused() {
        assertNull(WebRoutes.assetPath("https://pearlhub.net/..%2f..%2fshared_prefs/x.xml"))
        assertNull(WebRoutes.assetPath("https://pearlhub.net/..%2F"))
        assertNull(WebRoutes.assetPath("https://pearlhub.net/%2e%2e/secret"))
        assertNull(WebRoutes.assetPath("https://pearlhub.net/a/../../b"))
        assertNull(WebRoutes.assetPath("https://pearlhub.net/a/./b"))
        assertNull(WebRoutes.assetPath("https://pearlhub.net/a//b"))
        assertNull(WebRoutes.assetPath("https://pearlhub.net/a%5c..%5cb"))
        assertNull(WebRoutes.assetPath("https://pearlhub.net/a\\b"))
        assertNull(WebRoutes.assetPath("https://pearlhub.net/a%00.html"))
        assertNull(WebRoutes.assetPath("https://pearlhub.net/a%zz"))
        assertNull(WebRoutes.assetPath("https://pearlhub.net/a%2"))
        assertNull(WebRoutes.assetPath("https://pearlhub.net/%C3%28.html"))
    }

    @Test
    fun blockbookApiOnly() {
        assertTrue(WebRoutes.isBlockbookApi("https://blockbook.pearlresearch.ai/api/v2/"))
        assertTrue(WebRoutes.isBlockbookApi("https://blockbook.pearlresearch.ai/api/v2/xpub/tr(abc)?details=txs&page=2"))
        assertTrue(WebRoutes.isBlockbookApi("https://blockbook.testnet.pearlresearch.ai/api/v2/sendtx/"))
        assertFalse(WebRoutes.isBlockbookApi("https://blockbook.pearlresearch.ai/api"))
        assertFalse(WebRoutes.isBlockbookApi("https://blockbook.pearlresearch.ai/apix/"))
        assertFalse(WebRoutes.isBlockbookApi("https://blockbook.pearlresearch.ai/"))
        assertFalse(WebRoutes.isBlockbookApi("http://blockbook.pearlresearch.ai/api/v2/"))
        assertFalse(WebRoutes.isBlockbookApi("https://blockbook.pearlresearch.ai.evil.com/api/v2/"))
        assertFalse(WebRoutes.isBlockbookApi("https://x@blockbook.pearlresearch.ai/api/v2/"))
        assertFalse(WebRoutes.isBlockbookApi("https://blockbook.pearlresearch.ai/api/%2e%2e/admin"))
        assertFalse(WebRoutes.isBlockbookApi("https://blockbook.pearlresearch.ai/api/../admin"))
        assertFalse(WebRoutes.isBlockbookApi("https://pearlhub.net/api/v2/"))
    }

    @Test
    fun appUrlAndExternalSchemes() {
        assertTrue(WebRoutes.isAppUrl("https://pearlhub.net/privacy.html"))
        assertFalse(WebRoutes.isAppUrl("https://pearlhub.network/"))
        assertTrue(WebRoutes.isExternalAllowed("https://t.me/prl_hub"))
        assertTrue(WebRoutes.isExternalAllowed("http://example.com"))
        assertTrue(WebRoutes.isExternalAllowed("mailto:hi@pearlhub.net"))
        assertTrue(WebRoutes.isExternalAllowed("tg://resolve?domain=prl_hub"))
        assertTrue(WebRoutes.isExternalAllowed("HTTPS://pearlhub.net/"))
        assertFalse(WebRoutes.isExternalAllowed("javascript:alert(1)"))
        assertFalse(WebRoutes.isExternalAllowed("intent://x#Intent;end"))
        assertFalse(WebRoutes.isExternalAllowed("file:///sdcard/x"))
        assertFalse(WebRoutes.isExternalAllowed("content://x"))
        assertFalse(WebRoutes.isExternalAllowed("https:///nohost"))
        assertFalse(WebRoutes.isExternalAllowed("mailto:"))
        assertFalse(WebRoutes.isExternalAllowed("/relative"))
    }

    @Test
    fun startUrlAndRoutes() {
        assertEquals("https://pearlhub.net/?client=android&store=play", WebRoutes.startUrl("play"))
        assertEquals("https://pearlhub.net/?client=android&store=direct#workers", WebRoutes.startUrl("direct", "workers"))
        assertEquals("https://pearlhub.net/?client=android&store=play", WebRoutes.startUrl("play", "x\"><script>"))
        assertTrue(WebRoutes.isRoute("alerts"))
        assertFalse(WebRoutes.isRoute(""))
        assertFalse(WebRoutes.isRoute("Market"))
        assertFalse(WebRoutes.isRoute("a/b"))
    }

    @Test
    fun mimeTypes() {
        assertEquals("text/html", WebRoutes.mimeType("www/index.html"))
        assertEquals("text/javascript", WebRoutes.mimeType("www/a.mjs"))
        assertEquals("text/javascript", WebRoutes.mimeType("www/a.js"))
        assertEquals("text/css", WebRoutes.mimeType("www/a.css"))
        assertEquals("application/json", WebRoutes.mimeType("www/a.json"))
        assertEquals("image/png", WebRoutes.mimeType("www/a.PNG"))
        assertEquals("image/svg+xml", WebRoutes.mimeType("www/a.svg"))
        assertEquals("image/webp", WebRoutes.mimeType("www/a.webp"))
        assertEquals("image/x-icon", WebRoutes.mimeType("www/favicon.ico"))
        assertEquals("font/woff2", WebRoutes.mimeType("www/a.woff2"))
        assertEquals("text/plain", WebRoutes.mimeType("www/csp.txt"))
        assertEquals("application/manifest+json", WebRoutes.mimeType("www/manifest.webmanifest"))
        assertEquals("application/wasm", WebRoutes.mimeType("www/a.wasm"))
        assertEquals("application/octet-stream", WebRoutes.mimeType("www/LICENSE"))
        assertEquals("application/octet-stream", WebRoutes.mimeType("www/v.1/LICENSE"))
    }

    @Test
    fun colors() {
        assertEquals(0xFF101724.toInt(), WebRoutes.parseColor("#101724"))
        assertEquals(0xFFF4F6FC.toInt(), WebRoutes.parseColor("#f4f6fc"))
        assertNull(WebRoutes.parseColor("#fff"))
        assertNull(WebRoutes.parseColor("101724"))
        assertNull(WebRoutes.parseColor("#10172g"))
        assertNull(WebRoutes.parseColor("#+01724"))
        assertNull(WebRoutes.parseColor(null))
    }
}
