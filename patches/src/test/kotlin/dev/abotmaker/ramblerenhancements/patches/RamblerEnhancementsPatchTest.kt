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
                <Preference android:key="rambler_dictionary" />
                <androidx.preference.PreferenceCategory>
                    <Preference android:key="pref_key_jetson_feedback" />
                </androidx.preference.PreferenceCategory>
            </PreferenceScreen>
            """.trimIndent(),
        )
        val allFeatures = RamblerFeature.values().toSet()
        injectExhaustiveJetsonVoicePreferences(screen, allFeatures)
        injectExhaustiveJetsonVoicePreferences(screen, allFeatures)

        // Custom instructions is placed first, Help and feedback stays last.
        val topChildren = screen.documentElement.childElements()
        assertEquals(
            CUSTOM_INSTRUCTION_PREF_KEY,
            topChildren.first().childElements("EditTextPreference").single().androidAttr("key"),
        )
        assertEquals(
            "pref_key_jetson_feedback",
            topChildren.last().childElements("Preference").single().androidAttr("key"),
        )
        assertEquals(1, screen.getElementsByTagName("EditTextPreference").length)
        assertEquals(4, screen.getElementsByTagName("SwitchPreferenceCompat").length)
        val switchKeys = (0 until screen.getElementsByTagName("SwitchPreferenceCompat").length).map { idx ->
            (screen.getElementsByTagName("SwitchPreferenceCompat").item(idx) as org.w3c.dom.Element)
                .androidAttr("key")
        }
        assertEquals(
            listOf(
                USE_DICTIONARY_WORDS_PREF_KEY,
                APPEND_ONLY_PREF_KEY,
                CURSOR_EDITING_PREF_KEY,
                APP_CONTEXT_PREF_KEY,
            ),
            switchKeys,
        )

        // Partial selection only injects the chosen feature's rows.
        val waveformOnlyScreen = parseXml(
            """
            <PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android">
                <androidx.preference.PreferenceCategory>
                    <Preference android:key="pref_key_jetson_feedback" />
                </androidx.preference.PreferenceCategory>
            </PreferenceScreen>
            """.trimIndent(),
        )
        injectExhaustiveJetsonVoicePreferences(waveformOnlyScreen, setOf(RamblerFeature.WAVEFORM))
        assertEquals(0, waveformOnlyScreen.getElementsByTagName("EditTextPreference").length)
        assertEquals(0, waveformOnlyScreen.getElementsByTagName("SwitchPreferenceCompat").length)
        val waveformExtras = waveformOnlyScreen.getElementsByTagName("extra")
        assertEquals(1, waveformExtras.length)
        assertEquals("waveform", (waveformExtras.item(0) as org.w3c.dom.Element).androidAttr("value"))

        val voiceScreen = parseXml(
            """
            <PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android">
                <PreferenceCategory />
            </PreferenceScreen>
            """.trimIndent(),
        )
        injectSettingsEntryPreference(voiceScreen)
        injectSettingsEntryPreference(voiceScreen)
        assertEquals(1, voiceScreen.getElementsByTagName("Preference").length)
    }

    @Test
    fun patchesBundleJsonMatchesMorpheLocalDateTimeSchema() {
        val bundleFile = listOf(
            java.io.File("patches-bundle.json"),
            java.io.File("../patches-bundle.json"),
        ).first { it.isFile }
        val json = com.google.gson.JsonParser.parseString(bundleFile.readText()).asJsonObject
        val createdAt = json.get("created_at").asString
        // Morphe Manager deserializes created_at via java.time.LocalDateTime.parse (no 'Z' or offset!).
        val parsed = java.time.LocalDateTime.parse(createdAt)
        assertTrue(parsed.year >= 2026)
        assertTrue(json.get("download_url").asString.endsWith(".mpp"))
        assertTrue(json.get("version").asString.isNotBlank())
    }

    private fun parseXml(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(InputSource(StringReader(xml)))
}
