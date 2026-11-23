package dev.abotmaker.ramblerenhancements.patches

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class RamblerEnhancementsPatchTest {
    @Test
    fun catalogPublishesAllSixUnpinnedRamblerPatches() {
        val patches = PatchCatalog.morpheRegistrations
        assertEquals(6, patches.size)
        val names = patches.mapNotNull { it.name }.toSet()
        assertEquals(
            setOf(
                "Rambler Waveform Animation",
                "Rambler Custom Instructions",
                "Rambler Personal Dictionary",
                "Rambler Contact Names",
                "Rambler Cursor-Aware Editing",
                "Rambler App Context",
            ),
            names,
        )
        for (patch in patches) {
            val gboardCompat = patch.compatibility?.firstOrNull { it.packageName == GBOARD_PACKAGE }
            assertTrue("Expected $GBOARD_PACKAGE compatibility on ${patch.name}", gboardCompat != null)
            val targets = gboardCompat!!.targets
            assertTrue(
                "Patch ${patch.name} should use an unpinned wildcard AppTarget(version = null) so Morphe allows 18.5.4 and newer versions",
                targets.size == 1 && targets.single().version == null,
            )
            assertTrue(
                "Patch ${patch.name} description should note verified 18.5.4 + unverified newer builds",
                patch.description.orEmpty().contains("18.5.4"),
            )
        }
    }

    @Test
    fun manifestRegistrationAndSettingsInjectionAreIdempotent() {
        val manifest = parseXml(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.google.android.inputmethod.latin">
                <application />
            </manifest>
            """.trimIndent(),
        )
        registerSettingsActivity(manifest)
        registerSettingsActivity(manifest)
        val activities = manifest.documentElement.childElements("application").single().childElements("activity")
        assertEquals(1, activities.size)
        assertEquals("false", activities.single().androidAttr("exported"))
        assertEquals(SETTINGS_ACTIVITY_CLASS, activities.single().androidAttr("name"))

        val screen = parseXml(
            """
            <PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android">
                <androidx.preference.PreferenceCategory />
            </PreferenceScreen>
            """.trimIndent(),
        )
        injectSettingsEntryPreference(screen)
        injectSettingsEntryPreference(screen)
        addCustomInstructionPrefIdempotently(screen)
        addCustomInstructionPrefIdempotently(screen)
        val prefs = screen.getElementsByTagName("Preference")
        assertEquals(1, prefs.length)
        val edits = screen.getElementsByTagName("EditTextPreference")
        assertEquals(1, edits.length)
    }

    private fun parseXml(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(InputSource(StringReader(xml)))
}
