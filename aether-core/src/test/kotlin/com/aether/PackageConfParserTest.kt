package com.aether

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream

/**
 * Unit test for PackageConfParser — verifies the dual-encoding
 * (UTF-16LE header + UTF-8 body) Android-parcel string reader and
 * component classification against a synthetic package.conf.
 */
class PackageConfParserTest {

    /** Encode a UTF-8 length-prefixed parcel string (int32 len + bytes + null + pad). */
    private fun utf8(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        val ln = b.size
        out.write(ln and 0xff); out.write((ln ushr 8) and 0xff)
        out.write((ln ushr 16) and 0xff); out.write((ln ushr 24) and 0xff)
        out.write(b); out.write(0)
        var consumed = 4 + ln + 1
        while (consumed % 4 != 0) { out.write(0); consumed++ }
    }

    /** Encode a UTF-16LE length-prefixed parcel string (int32 charCount + chars + u16 null + pad). */
    private fun utf16(out: ByteArrayOutputStream, s: String) {
        val ln = s.length
        out.write(ln and 0xff); out.write((ln ushr 8) and 0xff)
        out.write((ln ushr 16) and 0xff); out.write((ln ushr 24) and 0xff)
        out.write(s.toByteArray(Charsets.UTF_16LE))
        out.write(0); out.write(0)
        var consumed = 4 + ln * 2 + 2
        while (consumed % 4 != 0) { out.write(0); consumed++ }
    }

    private fun synthetic(): ByteArray {
        val o = ByteArrayOutputStream()
        // UTF-16LE header (repackaged classloader name — like the real artifact)
        utf16(o, "androidx.appcompat.view.menu.u6")
        // UTF-8 body — components + metadata
        utf8(o, "com.miniclip.eightballpool.EightBallPoolApplication")
        utf8(o, "androidx.core.app.CoreComponentFactory")
        utf8(o, "com.miniclip.eightballpool.EightBallPoolActivity")
        utf8(o, "com.facebook.LoginActivity")
        utf8(o, "com.miniclip.eightballpool.BillingService")
        utf8(o, "com.google.firebase.provider.FirebaseInitProvider")
        utf8(o, "com.miniclip.eightballpool.BillingReceiver")
        utf8(o, "com.facebook.sdk.ApplicationId")
        utf8(o, "165073083517174")
        utf8(o, "56.23.2")
        utf8(o, "/data/app/~~AbC==/com.miniclip.eightballpool-XyZ==/base.apk")
        return o.toByteArray()
    }

    @Test
    fun parses_application_class() {
        val c = PackageConfParser.parse(synthetic())
        assertEquals("com.miniclip.eightballpool.EightBallPoolApplication", c.applicationClass)
    }

    @Test
    fun parses_app_component_factory() {
        val c = PackageConfParser.parse(synthetic())
        assertEquals("androidx.core.app.CoreComponentFactory", c.appComponentFactory)
    }

    @Test
    fun parses_version_and_apk_path() {
        val c = PackageConfParser.parse(synthetic())
        assertEquals("56.23.2", c.version)
        assertTrue(c.apkPath!!.endsWith("base.apk"))
    }

    @Test
    fun classifies_components() {
        val c = PackageConfParser.parse(synthetic())
        assertTrue(c.activities.contains("com.miniclip.eightballpool.EightBallPoolActivity"))
        assertTrue(c.activities.contains("com.facebook.LoginActivity"))
        assertTrue(c.services.contains("com.miniclip.eightballpool.BillingService"))
        assertTrue(c.providers.contains("com.google.firebase.provider.FirebaseInitProvider"))
        assertTrue(c.receivers.contains("com.miniclip.eightballpool.BillingReceiver"))
    }

    @Test
    fun extracts_metadata() {
        val c = PackageConfParser.parse(synthetic())
        assertEquals("165073083517174", c.metadata["com.facebook.sdk.ApplicationId"])
    }

    @Test
    fun launcher_prefers_eightball_activity() {
        val c = PackageConfParser.parse(synthetic())
        assertEquals("com.miniclip.eightballpool.EightBallPoolActivity", c.launcherActivity)
    }

    @Test
    fun all_components_includes_application_and_dedups() {
        val c = PackageConfParser.parse(synthetic())
        val all = c.allComponents()
        assertTrue(all.contains("com.miniclip.eightballpool.EightBallPoolApplication"))
        assertEquals(all.size, all.distinct().size)
    }

    @Test
    fun empty_input_yields_empty_components() {
        val c = PackageConfParser.parse(ByteArray(0))
        assertTrue(c.activities.isEmpty())
        assertNull(c.applicationClass)
    }
}
