package dev.abotmaker.ramblerenhancements.patches

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

class RamblerEnhancementsPatchTest {
    @Test
    fun catalogPublishesAllSixSingleAppGboardRamblerPatches() {
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
            // Single-app bundle (only com.google.android.inputmethod.latin) ensures Morphe Manager
            // recognizes it via soleAppByUid and displays "Update available / Repatch" badges on
            // Gboard whenever a new release ships, and named targets expose it in "Need help finding the APK?".
            assertEquals(1, patch.compatibility?.size)
            val gboardCompat = patch.compatibility!!.single()
            assertEquals(GBOARD_PACKAGE, gboardCompat.packageName)
            val targets = gboardCompat.targets
            assertTrue(
                "Patch ${patch.name} should declare stable verified target $VERIFIED_GBOARD_VERSION",
                targets.any { it.version == VERIFIED_GBOARD_VERSION && !it.isExperimental },
            )
            assertTrue(
                "Patch ${patch.name} should declare experimental target $EXPERIMENTAL_GBOARD_VERSION",
                targets.any { it.version == EXPERIMENTAL_GBOARD_VERSION && it.isExperimental },
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

    @Test
    fun dynamicConfigClinitAllocatesRegistersEvenWhenOriginalHadZero() {
        val zeroRegClinit = com.android.tools.smali.dexlib2.immutable.ImmutableMethod(
            DYNAMIC_CONFIG_TYPE,
            "<clinit>",
            emptyList(),
            "V",
            com.android.tools.smali.dexlib2.AccessFlags.STATIC.value or
                com.android.tools.smali.dexlib2.AccessFlags.CONSTRUCTOR.value,
            emptySet(),
            emptySet(),
            com.android.tools.smali.dexlib2.builder.MutableMethodImplementation(0),
        )
        val configClass = app.morphe.patcher.util.proxy.mutableTypes.MutableClass(
            com.android.tools.smali.dexlib2.immutable.ImmutableClassDef(
                DYNAMIC_CONFIG_TYPE,
                com.android.tools.smali.dexlib2.AccessFlags.PUBLIC.value or
                    com.android.tools.smali.dexlib2.AccessFlags.FINAL.value,
                "Ljava/lang/Object;",
                emptyList(),
                null,
                emptySet(),
                emptyList(),
                listOf(zeroRegClinit),
            ),
        )
        val state = RamblerPatchRunState().apply {
            selectedFeatures.addAll(RamblerFeature.values())
        }
        rewriteDynamicConfigClinitOnClass(configClass, state)

        val clinit = configClass.methods.single { it.name == "<clinit>" }
        val impl = clinit.implementation!!
        assertTrue(
            "Expected registerCount >= 1 so v0 passes ART DEX verification, got ${impl.registerCount}",
            impl.registerCount >= 1,
        )
        assertTrue("Expected non-empty <clinit> instructions", impl.instructions.size > 20)
        assertEquals(com.android.tools.smali.dexlib2.Opcode.RETURN_VOID, impl.instructions.last().opcode)
    }

    @Test
    fun helperMethodRetainsTryCatchMetadataForMoveException() {
        val host = app.morphe.patcher.util.proxy.mutableTypes.MutableClass(
            com.android.tools.smali.dexlib2.immutable.ImmutableClassDef(
                "Lcom/example/Host;",
                com.android.tools.smali.dexlib2.AccessFlags.PUBLIC.value,
                "Ljava/lang/Object;",
                emptyList(),
                null,
                emptySet(),
                emptyList(),
                emptyList(),
            ),
        )
        host.addHelperMethodIfMissing(
            name = "safeCommit",
            parameterTypes = listOf("Ljava/lang/Object;"),
            returnType = "Z",
            accessFlags = com.android.tools.smali.dexlib2.AccessFlags.PRIVATE.value or
                com.android.tools.smali.dexlib2.AccessFlags.STATIC.value,
            registerCount = 3,
            body = """
                :start
                invoke-static {}, $FLAG_RUNTIME_TYPE->isAppendOnlyEnabled()Z
                move-result v0
                if-eqz v0, :commit
                :end
                .catch Ljava/lang/Throwable; {:start .. :end} :failed
                goto :commit
                :failed
                move-exception v0
                :commit
                const/4 v0, 0x1
                return v0
            """,
        )
        val helper = host.methods.single { it.name == "safeCommit" }
        val impl = helper.implementation!!
        assertEquals(1, impl.tryBlocks.size)
        val handler = impl.tryBlocks.single().exceptionHandlers.single()
        assertEquals("Ljava/lang/Throwable;", handler.exceptionType)
        val moveExIndex = impl.instructions.indexOfFirst {
            it.opcode == com.android.tools.smali.dexlib2.Opcode.MOVE_EXCEPTION
        }
        assertTrue("Expected move-exception instruction", moveExIndex > 0)
        assertEquals(
            impl.instructions[moveExIndex].location.codeAddress,
            handler.handlerCodeAddress,
        )
    }

    @Test
    fun flagHookInsertionTargetsFirstPostLoopSnapshotCheckBeforeBranchReusesV0() {
        val dummyField = com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference(
            "Loki;",
            "e",
            "Z",
        )
        val instructions = listOf(
            // 0: arrayLoadIndex (aget-object v8, v8, v7)
            com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction23x(
                com.android.tools.smali.dexlib2.Opcode.AGET_OBJECT,
                8,
                8,
                7,
            ),
            // 1: if-eqz v8, +4
            com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t(
                com.android.tools.smali.dexlib2.Opcode.IF_EQZ,
                8,
                4,
            ),
            // 2: const/4 v5, 1
            com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n(
                com.android.tools.smali.dexlib2.Opcode.CONST_4,
                5,
                1,
            ),
            // 3: FIRST if-eqz v0 (if (okrVar != null)) -> MUST select this index!
            com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t(
                com.android.tools.smali.dexlib2.Opcode.IF_EQZ,
                0,
                10,
            ),
            // 4: iget-boolean v0, v10, Loki;.e:Z (reuses v0 inside okrVar != null branch)
            com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c(
                com.android.tools.smali.dexlib2.Opcode.IGET_BOOLEAN,
                0,
                10,
                dummyField,
            ),
            // 5: SECOND if-eqz v0 (if (this.e)) -> MUST NOT select this!
            com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t(
                com.android.tools.smali.dexlib2.Opcode.IF_EQZ,
                0,
                4,
            ),
            // 6: placeholder equalsIndex
            com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x(
                com.android.tools.smali.dexlib2.Opcode.NOP,
            ),
        )
        val chosen = RamblerDynamicResolver.findPostLoopSnapshotCheckIndex(
            instructions = instructions,
            arrayLoadIndex = 0,
            equalsIndex = 6,
            snapshotReg = 0,
        )
        assertEquals(3, chosen)
    }

    private fun parseXml(xml: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
    }.newDocumentBuilder().parse(InputSource(StringReader(xml)))
}
