package dev.abotmaker.ramblerenhancements.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val CUSTOM_INSTRUCTION_PREF_KEY = "pref_key_rambler_custom_instruction"
private const val JETSON_VOICE_SETTINGS_XML = "res/xml/setting_jetson_voice.xml"
private const val APPEND_COMMIT_HELPER = "ramblerEnhancementsAppendCommit"
private const val CHAR_SEQUENCE_TYPE = "Ljava/lang/CharSequence;"

private const val VERSION_NOTE =
    "Verified on Gboard 18.5.4 (current GboardWu target); newer versions are best-effort and unverified."

@Suppress("unused")
val ramblerWaveformAnimationPatch = bytecodePatch(
    name = "Rambler Waveform Animation",
    description = "Replaces Gboard Rambler's light bar with Hex by Anomaly's animations. " +
        "Listening (while you speak): Hex Dot Scope (braided dot oscilloscope), Hex Android Wave (7-row scrolling sine wave), or Gboard Default. " +
        "Processing (after you stop speaking while transcribing): Flow, Pulse, or Sweep. " +
        "Includes independent Listening/Processing colors and minimal Hex tuning (Strip trace vs iOS halo, dot spacing, glow, voice response). " +
        "Works alongside upstream GboardWu (requires Advanced Voice Typing in GboardWu). $VERSION_NOTE",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD, COMPATIBILITY_GBOARD_WU)
    dependsOn(ramblerSharedExtensionPatch, ramblerSharedSettingsResourcePatch)

    execute {
        val state = RamblerPatchStateStore.state(this)
        state.selectedFeatures.add(RamblerFeature.WAVEFORM)

        val targets = RamblerDynamicResolver.resolveWaveformTargets(this)
        state.waveformTargets = targets

        val attachImpl = targets.attachMethod.implementation
            ?: throw PatchException("Rambler attach method has no implementation")
        if (attachImpl.registerCount !in 3..16) {
            throw PatchException("Rambler attach register count out of range: ${attachImpl.registerCount}")
        }
        val speechImpl = targets.speechLevelMethod.implementation
            ?: throw PatchException("Rambler speech-level method has no implementation")
        if (speechImpl.registerCount !in 3..16) {
            throw PatchException("Rambler speech-level register count out of range: ${speechImpl.registerCount}")
        }
        val drawImpl = targets.drawMethod.implementation
            ?: throw PatchException("Rambler draw method has no implementation")
        if (drawImpl.registerCount !in 2..16) {
            throw PatchException("Rambler draw register count out of range: ${drawImpl.registerCount}")
        }
        val firstDrawInstruction = drawImpl.instructions.firstOrNull()
            ?: throw PatchException("Rambler draw method has no instructions")

        targets.attachMethod.addInstructions(
            0,
            "invoke-static {p1, p2}, $WAVEFORM_RUNTIME_TYPE->onAttach(Landroid/view/View;Landroid/graphics/drawable/Drawable;)V",
        )

        targets.speechLevelMethod.addInstructions(
            0,
            "invoke-static {p0, p1, p2}, $WAVEFORM_RUNTIME_TYPE->onSpeechLevel(ILandroid/graphics/drawable/Drawable;Landroid/view/View;)V",
        )

        targets.drawMethod.addInstructionsWithLabels(
            0,
            """
            invoke-static {p0, p1}, $WAVEFORM_RUNTIME_TYPE->maybeDraw(Landroid/graphics/drawable/Drawable;Landroid/graphics/Canvas;)Z
            move-result v0
            if-eqz v0, :rambler_waveform_stock_draw
            return-void
            """.trimIndent(),
            ExternalLabel("rambler_waveform_stock_draw", firstDrawInstruction),
        )
    }
}

private val ramblerCustomInstructionsResourcePatch = resourcePatch(
    description = "Add Custom instructions EditTextPreference to Gboard's Rambler voice settings.",
) {
    compatibleWith(COMPATIBILITY_GBOARD, COMPATIBILITY_GBOARD_WU)

    execute {
        val textsByFolder = mapOf(
            "values" to Triple(
                "Custom instructions",
                "Style rules Rambler applies to everything you dictate, such as spelling, punctuation, or tone",
                "Added to Rambler instructions. For example: Write every number in digits.",
            ),
            "values-zh-rTW" to Triple(
                "自訂指示",
                "Rambler 每次語音輸入時套用的風格規則，例如拼字、標點或語氣",
                "會附加到 Rambler 的預設指示中。例如：所有數字一律使用阿拉伯數字。",
            ),
            "values-b+zh+Hant" to Triple(
                "自訂指示",
                "Rambler 每次語音輸入時套用的風格規則，例如拼字、標點或語氣",
                "會附加到 Rambler 的預設指示中。例如：所有數字一律使用阿拉伯數字。",
            ),
        )
        for ((folder, texts) in textsByFolder) {
            val path = "res/$folder/strings.xml"
            if (folder != "values" && !get(path).isFile) continue
            document(path).use { doc ->
                ensureString(doc, "rambler_enh_custom_instruction_title", texts.first)
                ensureString(doc, "rambler_enh_custom_instruction_summary", texts.second)
                ensureString(doc, "rambler_enh_custom_instruction_hint", texts.third)
            }
        }
        if (get(JETSON_VOICE_SETTINGS_XML).isFile) {
            document(JETSON_VOICE_SETTINGS_XML).use(::addCustomInstructionPrefIdempotently)
        }
    }
}

internal fun addCustomInstructionPrefIdempotently(screenDoc: Document) {
    val root = screenDoc.documentElement
    val all = screenDoc.getElementsByTagName("*")
    for (i in 0 until all.length) {
        val el = all.item(i) as? Element ?: continue
        if (el.androidAttr("key") == CUSTOM_INSTRUCTION_PREF_KEY) {
            return
        }
    }
    val categoryTag = root.childElements()
        .firstOrNull { it.tagName.endsWith("PreferenceCategory") }
        ?.tagName
        ?: "androidx.preference.PreferenceCategory"

    val category = screenDoc.createElement(categoryTag)
    val field = screenDoc.createElement("EditTextPreference").apply {
        setAndroidAttr("key", CUSTOM_INSTRUCTION_PREF_KEY)
        setAndroidAttr("persistent", "true")
        setAndroidAttr("defaultValue", "")
        setAndroidAttr("title", "@string/rambler_enh_custom_instruction_title")
        setAndroidAttr("summary", "@string/rambler_enh_custom_instruction_summary")
        setAndroidAttr("dialogTitle", "@string/rambler_enh_custom_instruction_title")
        setAndroidAttr("dialogMessage", "@string/rambler_enh_custom_instruction_hint")
    }
    category.appendChild(field)
    root.insertBefore(category, root.firstChild)
}

@Suppress("unused")
val ramblerCustomInstructionsPatch = bytecodePatch(
    name = "Rambler Custom Instructions",
    description = "Enables persistent custom style/formatting instructions for Gboard Rambler (credited to nl-morphe-patches) and exposes them in Gboard Settings -> Voice typing -> Rambler Enhancements. $VERSION_NOTE",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD, COMPATIBILITY_GBOARD_WU)
    dependsOn(
        ramblerSharedExtensionPatch,
        ramblerSharedSettingsResourcePatch,
        ramblerCustomInstructionsResourcePatch,
    )

    execute {
        RamblerPatchStateStore.state(this).selectedFeatures.add(RamblerFeature.CUSTOM_INSTRUCTIONS)
    }
}

@Suppress("unused")
val ramblerPersonalDictionaryPatch = bytecodePatch(
    name = "Rambler Personal Dictionary",
    description = "Enables Gboard Rambler's UserFeatureCache MUSE_STATS personal voice dictionary (credited to nl-morphe-patches) with add, import/export (.txt), remove, and clear controls in Rambler Enhancements. $VERSION_NOTE",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD, COMPATIBILITY_GBOARD_WU)
    dependsOn(ramblerSharedExtensionPatch, ramblerSharedSettingsResourcePatch)

    execute {
        RamblerPatchStateStore.state(this).selectedFeatures.add(RamblerFeature.PERSONAL_DICTIONARY)
    }
}

@Suppress("unused")
val ramblerContactNamesPatch = bytecodePatch(
    name = "Rambler Contact Names",
    description = "Seeds up to 1,000 phone contact names into Rambler's MUSE_STATS voice dictionary (credited to nl-morphe-patches) when Contacts permission is granted, plus manual sync and reset controls. $VERSION_NOTE",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD, COMPATIBILITY_GBOARD_WU)
    dependsOn(
        ramblerPersonalDictionaryPatch,
        ramblerSharedExtensionPatch,
        ramblerSharedSettingsResourcePatch,
    )

    execute {
        RamblerPatchStateStore.state(this).selectedFeatures.add(RamblerFeature.CONTACT_NAMES)
    }
}

@Suppress("unused")
val ramblerCursorEditingPatch = bytecodePatch(
    name = "Rambler Cursor-Aware Editing",
    description = "Enables cursor-aware editing, selected-text-only spoken edits, post-edit reselection (credited to nl-morphe-patches), and a live Append-only dictation toggle with clean cursor-boundary spacing. $VERSION_NOTE",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD, COMPATIBILITY_GBOARD_WU)
    dependsOn(ramblerSharedExtensionPatch, ramblerSharedSettingsResourcePatch)

    execute {
        val state = RamblerPatchStateStore.state(this)
        state.selectedFeatures.add(RamblerFeature.CURSOR_EDITING)

        val agentic = state.agenticTargets ?: RamblerDynamicResolver.resolveAgenticExtension(this)
            .also { state.agenticTargets = it }
        val spacing = RamblerDynamicResolver.resolveAppendSpacingTargets(this, agentic.extensionClass)

        val helperBody = """
            :start
            invoke-static {}, $FLAG_RUNTIME_TYPE->isAppendOnlyEnabled()Z
            move-result v0
            if-eqz v0, :commit
            const/4 v0, 0x1
            const/4 v1, 0x0
            invoke-interface {p0, v0, v0, v1}, ${spacing.pcnType}->${spacing.surroundingMethodName}(III)${spacing.pdxType}
            move-result-object v2
            if-eqz v2, :commit
            invoke-virtual {v2}, ${spacing.pdxType}->${spacing.beforeCursorMethodName}()$CHAR_SEQUENCE_TYPE
            move-result-object v3
            invoke-virtual {v2}, ${spacing.pdxType}->${spacing.afterCursorMethodName}()$CHAR_SEQUENCE_TYPE
            move-result-object v4
            invoke-static {v3, v4, p1}, $FLAG_RUNTIME_TYPE->adjustAppendSpacing($CHAR_SEQUENCE_TYPE$CHAR_SEQUENCE_TYPE$CHAR_SEQUENCE_TYPE)$CHAR_SEQUENCE_TYPE
            move-result-object v5
            if-eqz v5, :commit
            move-object p1, v5
            :end
            .catch Ljava/lang/Throwable; {:start .. :end} :failed
            goto :commit
            :failed
            move-exception v0
            :commit
            const/4 v0, 0x0
            invoke-interface {p0, v0, v0, p1}, ${spacing.pcnType}->${spacing.commitMethodName}(II$CHAR_SEQUENCE_TYPE)Z
            move-result v0
            return v0
        """.trimIndent()

        spacing.extensionClass.addHelperMethodIfMissing(
            name = APPEND_COMMIT_HELPER,
            parameterTypes = listOf(spacing.pcnType, CHAR_SEQUENCE_TYPE),
            returnType = "Z",
            accessFlags = AccessFlags.PRIVATE.value or AccessFlags.STATIC.value,
            registerCount = 8,
            body = helperBody,
        )

        spacing.targetMethod.replaceInstruction(
            spacing.callIndex,
            "invoke-static {v${spacing.pcnRegister}, v${spacing.textRegister}}, " +
                "${spacing.extensionClass.type}->$APPEND_COMMIT_HELPER(${spacing.pcnType}$CHAR_SEQUENCE_TYPE)Z",
        )
    }
}

@Suppress("unused")
val ramblerAppContextPatch = bytecodePatch(
    name = "Rambler App Context",
    description = "Enables sharing the active app name, input type, and field hint with Gboard Rambler (credited to nl-morphe-patches) with a live toggle in Rambler Enhancements. $VERSION_NOTE",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GBOARD, COMPATIBILITY_GBOARD_WU)
    dependsOn(ramblerSharedExtensionPatch, ramblerSharedSettingsResourcePatch)

    execute {
        RamblerPatchStateStore.state(this).selectedFeatures.add(RamblerFeature.APP_CONTEXT)
    }
}
