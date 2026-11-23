package dev.abotmaker.ramblerenhancements.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.ResourcePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.util.WeakHashMap

internal const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
internal const val VERIFIED_GBOARD_VERSION = "18.5.4.993608717-release-arm64-v8a"
internal const val EXPERIMENTAL_GBOARD_VERSION = "18.8.0.1022711984-beta-arm64-v8a"
internal val COMPATIBILITY_GBOARD = Compatibility(
    name = "Gboard",
    packageName = GBOARD_PACKAGE,
    appIconColor = 0x1A73E8,
    targets = listOf(
        AppTarget(
            version = VERIFIED_GBOARD_VERSION,
            isExperimental = false,
            description = "Verified target (works alongside Gboard Patches / GboardWu).",
        ),
        AppTarget(
            version = EXPERIMENTAL_GBOARD_VERSION,
            isExperimental = true,
            description = "Experimental standalone target (without Gboard Patches).",
        ),
    ),
)
internal const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
internal const val OPEN_SETTINGS_ACTION = "dev.abotmaker.ramblerenhancements.OPEN_SETTINGS"
internal const val SETTINGS_ACTIVITY_CLASS =
    "dev.abotmaker.ramblerenhancements.RamblerEnhancementsActivity"
internal const val DYNAMIC_CONFIG_TYPE =
    "Ldev/abotmaker/ramblerenhancements/RamblerDynamicConfig;"
internal const val FLAG_RUNTIME_TYPE =
    "Ldev/abotmaker/ramblerenhancements/RamblerFlagRuntime;"
internal const val CONTACTS_RUNTIME_TYPE =
    "Ldev/abotmaker/ramblerenhancements/RamblerContactsRuntime;"
internal const val WAVEFORM_RUNTIME_TYPE =
    "Ldev/abotmaker/ramblerenhancements/RamblerWaveformRuntime;"
internal const val PREF_LAUNCHER_TYPE =
    "Ldev/abotmaker/ramblerenhancements/RamblerPreferenceLauncher;"

internal enum class RamblerFeature {
    WAVEFORM,
    CUSTOM_INSTRUCTIONS,
    PERSONAL_DICTIONARY,
    CONTACT_NAMES,
    CURSOR_EDITING,
    APP_CONTEXT,
}

internal class RamblerPatchRunState {
    val selectedFeatures = linkedSetOf<RamblerFeature>()
    var flagTargets: FlagTargets? = null
    var agenticTargets: AgenticExtensionTargets? = null
    var waveformTargets: WaveformTargets? = null
    var museStatsTargets: MuseStatsTargets? = null
    var prefsTargets: PrefsTargets? = null
    var prefLaunchTargets: PreferenceLaunchTargets? = null
    var agenticCtorHooked: Boolean = false
}

internal object RamblerPatchStateStore {
    private val byContext = WeakHashMap<BytecodePatchContext, RamblerPatchRunState>()

    @Synchronized
    fun state(context: BytecodePatchContext): RamblerPatchRunState =
        byContext.getOrPut(context) { RamblerPatchRunState() }

    @Synchronized
    fun take(context: BytecodePatchContext): RamblerPatchRunState? =
        byContext.remove(context)
}

internal val ramblerSharedExtensionPatch = bytecodePatch(
    description = "Attach Rambler Enhancements runtime extension and wire dynamically fingerprinted bindings.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)
    extendWith("extensions/rambler-enhancements.rve")

    execute { }

    finalize {
        val state = RamblerPatchStateStore.take(this) ?: return@finalize
        if (state.selectedFeatures.isEmpty()) return@finalize

        val needsFlags = state.selectedFeatures.any {
            it == RamblerFeature.CUSTOM_INSTRUCTIONS ||
                it == RamblerFeature.PERSONAL_DICTIONARY ||
                it == RamblerFeature.CURSOR_EDITING ||
                it == RamblerFeature.APP_CONTEXT
        }

        if (needsFlags) {
            val flags = state.flagTargets ?: RamblerDynamicResolver.resolveFlagTargets(this).also {
                state.flagTargets = it
            }
            installConflictFreeFlagHook(flags)
        }

        val needsAgenticCtorHook =
            RamblerFeature.PERSONAL_DICTIONARY in state.selectedFeatures ||
                RamblerFeature.CONTACT_NAMES in state.selectedFeatures ||
                RamblerFeature.CURSOR_EDITING in state.selectedFeatures ||
                RamblerFeature.APP_CONTEXT in state.selectedFeatures
        if (needsAgenticCtorHook && !state.agenticCtorHooked) {
            val agentic = state.agenticTargets ?: RamblerDynamicResolver.resolveAgenticExtension(this)
                .also { state.agenticTargets = it }
            installAgenticCtorStartupHook(agentic)
            state.agenticCtorHooked = true
        }

        if (RamblerFeature.PERSONAL_DICTIONARY in state.selectedFeatures) {
            val agentic = state.agenticTargets ?: RamblerDynamicResolver.resolveAgenticExtension(this)
                .also { state.agenticTargets = it }
            if (state.museStatsTargets == null) {
                state.museStatsTargets = RamblerDynamicResolver.resolveMuseStatsTargets(
                    this,
                    agentic.contextConstructor,
                )
            }
        }

        val needsPrefs = RamblerFeature.CUSTOM_INSTRUCTIONS in state.selectedFeatures ||
            RamblerFeature.PERSONAL_DICTIONARY in state.selectedFeatures ||
            RamblerFeature.CURSOR_EDITING in state.selectedFeatures ||
            RamblerFeature.APP_CONTEXT in state.selectedFeatures
        if (needsPrefs) {
            val agentic = state.agenticTargets ?: RamblerDynamicResolver.resolveAgenticExtension(this)
                .also { state.agenticTargets = it }
            if (state.prefsTargets == null) {
                state.prefsTargets = RamblerDynamicResolver.resolvePrefsTargets(
                    this,
                    agentic.extensionClass,
                )
            }
        }

        val prefLaunch = RamblerDynamicResolver.resolvePreferenceLaunchTargets(this)
        state.prefLaunchTargets = prefLaunch
        installExplicitPreferenceLaunchHook(prefLaunch)

        rewriteDynamicConfigClinit(state)
    }
}

private fun installExplicitPreferenceLaunchHook(targets: PreferenceLaunchTargets) {
    val impl = targets.performClickMethod.implementation
        ?: throw PatchException("Preference.performClick has no implementation")
    if (impl.registerCount !in 2..16) {
        throw PatchException(
            "Preference.performClick register count out of range: ${impl.registerCount}",
        )
    }
    val firstInsn = impl.instructions.firstOrNull()
        ?: throw PatchException("Preference.performClick has no instructions")
    targets.performClickMethod.addInstructionsWithLabels(
        0,
        """
        invoke-static {p0}, $PREF_LAUNCHER_TYPE->maybeLaunch(Ljava/lang/Object;)Z
        move-result v0
        if-eqz v0, :rambler_stock_pref_click
        return-void
        """.trimIndent(),
        ExternalLabel("rambler_stock_pref_click", firstInsn),
    )
}

private fun installConflictFreeFlagHook(targets: FlagTargets) {
    val vReg = targets.resolvedValueRegister
    targets.recomputeMethod.addInstructions(
        targets.insertIndex,
        """
        invoke-static {p0, v$vReg}, $FLAG_RUNTIME_TYPE->applyResolvedFlag(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object v$vReg
        """.trimIndent(),
    )
}

internal fun installAgenticCtorStartupHook(targets: AgenticExtensionTargets) {
    val insns = targets.contextConstructor.implementation?.instructions
        ?: throw PatchException("AgenticDictationExtension.<init> has no instructions")
    val returnIndices = insns.indices.filter { insns[it].opcode == Opcode.RETURN_VOID }
    if (returnIndices.isEmpty()) {
        throw PatchException("AgenticDictationExtension.<init> has no return-void")
    }
    val pIdx = targets.contextParamIndex
    for (returnIdx in returnIndices.asReversed()) {
        targets.contextConstructor.addInstructions(
            returnIdx,
            "invoke-static {p$pIdx}, $CONTACTS_RUNTIME_TYPE->onRamblerStart(Landroid/content/Context;)V",
        )
    }
}

private fun BytecodePatchContext.rewriteDynamicConfigClinit(state: RamblerPatchRunState) {
    val configClass = mutableClassDefBy(DYNAMIC_CONFIG_TYPE)
    rewriteDynamicConfigClinitOnClass(configClass, state)
}

internal fun rewriteDynamicConfigClinitOnClass(
    configClass: MutableClass,
    state: RamblerPatchRunState,
) {
    // D8 folds static constant initializers into DEX static_values and may emit a 0-register
    // <clinit> (or omit <clinit> entirely). Replacing it with a fresh MutableMethodImplementation
    // guarantees registerCount >= 1 so ART never rejects v0 with VerifyError (0 >= 0).
    configClass.methods.removeAll { it.name == "<clinit>" }
    val clinit = ImmutableMethod(
        configClass.type,
        "<clinit>",
        emptyList(),
        "V",
        AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value,
        emptySet(),
        emptySet(),
        MutableMethodImplementation(2),
    ).toMutable()

    val sb = StringBuilder()

    fun putBool(fieldName: String, value: Boolean) {
        sb.appendLine("const/4 v0, ${if (value) "0x1" else "0x0"}")
        sb.appendLine("sput-boolean v0, $DYNAMIC_CONFIG_TYPE->$fieldName:Z")
    }

    fun putStr(fieldName: String, value: String) {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        sb.appendLine("const-string v0, \"$escaped\"")
        sb.appendLine("sput-object v0, $DYNAMIC_CONFIG_TYPE->$fieldName:Ljava/lang/String;")
    }

    // Selected feature gates
    putBool("FEATURE_WAVEFORM", RamblerFeature.WAVEFORM in state.selectedFeatures)
    putBool("FEATURE_CUSTOM_INSTRUCTIONS", RamblerFeature.CUSTOM_INSTRUCTIONS in state.selectedFeatures)
    putBool("FEATURE_PERSONAL_DICTIONARY", RamblerFeature.PERSONAL_DICTIONARY in state.selectedFeatures)
    putBool("FEATURE_CONTACT_NAMES", RamblerFeature.CONTACT_NAMES in state.selectedFeatures)
    putBool("FEATURE_CURSOR_EDITING", RamblerFeature.CURSOR_EDITING in state.selectedFeatures)
    putBool("FEATURE_APP_CONTEXT", RamblerFeature.APP_CONTEXT in state.selectedFeatures)

    // Flag bindings (resolved or 18.5.4 defaults)
    val flags = state.flagTargets
    putStr("FLAG_NAME_FIELD", flags?.flagNameField ?: "a")
    putStr("FLAG_RECOMPUTE_METHOD", flags?.recomputeMethod?.name ?: "x")
    putStr("FLAG_NOTIFY_METHOD", flags?.notifyMethod ?: "t")

    // Waveform bindings (resolved or 18.5.4 defaults)
    val wf = state.waveformTargets
    putStr("DRAWABLE_SHADER_FIELD", wf?.drawableShaderField ?: "e")
    putStr("DRAWABLE_CORNER_RADIUS_FIELD", wf?.drawableCornerRadiusField ?: "h")
    putStr("SHADER_PROCESSING_FIELD", wf?.shaderProcessingField ?: "l")
    putStr("SHADER_COLLAPSING_FIELD", wf?.shaderCollapsingField ?: "i")
    putStr("SHADER_EXIT_FADE_FIELD", wf?.shaderExitFadeField ?: "j")
    putStr("SHADER_GLOBAL_ALPHA_FIELD", wf?.shaderGlobalAlphaField ?: "o")

    // MUSE_STATS / Protobuf bindings (resolved or 18.5.4 defaults)
    val ms = state.museStatsTargets
    putStr("MODULE_LOAD_METHOD", ms?.moduleLoadMethod ?: "b")
    putStr("UFC_INTERFACE_CLASS", ms?.ufcInterfaceClass ?: "see")
    putStr("UFC_FEATURE_ENUM_CLASS", ms?.ufcFeatureEnumClass ?: "sdo")
    putStr("UFC_ENUM_FROM_ID_METHOD", ms?.ufcEnumFromIdMethod ?: "a")
    putStr("UFC_READ_METHOD", ms?.ufcReadMethod ?: "c")
    putStr("UFC_UPDATE_METHOD", ms?.ufcUpdateMethod ?: "n")
    putStr("FUNCTION_INTERFACE_CLASS", ms?.functionInterfaceClass ?: "wgh")
    putStr("MUSE_STATS_CLASS", ms?.museStatsClass ?: "scf")
    putStr("MUSE_STATS_DEFAULT_FIELD", ms?.museStatsDefaultField ?: "a")
    putStr("MUSE_STATS_WORDS_FIELD", ms?.museStatsWordsField ?: "b")
    putStr("MUSE_WORD_CLASS", ms?.museWordClass ?: "sce")
    putStr("MUSE_WORD_DEFAULT_FIELD", ms?.museWordDefaultField ?: "a")
    putStr("MUSE_WORD_TEXT_FIELD", ms?.museWordTextField ?: "b")
    putStr("PROTO_BASE_CLASS", ms?.protoBaseClass ?: "aaln")
    putStr("PROTO_BUILDER_CLASS", ms?.protoBuilderClass ?: "aali")
    putStr("PROTO_LIST_CLASS", ms?.protoListClass ?: "aamd")
    putStr("PROTO_PARSE_METHOD", ms?.protoParseMethod ?: "t")
    putStr("PROTO_TO_BUILDER_METHOD", ms?.protoToBuilderMethod ?: "o")
    putStr("PROTO_MUTABLE_COPY_METHOD", ms?.protoMutableCopyMethod ?: "E")
    putStr("BUILDER_COPY_ON_WRITE_METHOD", ms?.builderCopyOnWriteMethod ?: "t")
    putStr("BUILDER_BUILD_METHOD", ms?.builderBuildMethod ?: "o")
    putStr("BUILDER_INSTANCE_FIELD", ms?.builderInstanceField ?: "b")
    putStr("LIST_IS_MODIFIABLE_METHOD", ms?.listIsModifiableMethod ?: "c")

    // Preferences bindings (resolved or 18.5.4 defaults)
    val prefs = state.prefsTargets
    putStr("PREFS_CLASS", prefs?.prefsClass ?: "qvt")
    putStr("PREFS_GET_INSTANCE_METHOD", prefs?.getInstanceMethod ?: "I")
    putStr("PREFS_READ_STRING_METHOD", prefs?.readStringMethod ?: "d")
    putStr("PREFS_WRITE_STRING_METHOD", prefs?.writeStringMethod ?: "i")

    // AndroidX Preference launch bindings (resolved or 18.5.4 defaults)
    val prefLaunch = state.prefLaunchTargets
    putStr("PREF_CONTEXT_FIELD", prefLaunch?.contextField ?: "j")
    putStr("PREF_INTENT_FIELD", prefLaunch?.intentField ?: "s")

    sb.appendLine("return-void")
    clinit.addInstructions(0, sb.toString().trimIndent())
    configClass.methods.add(clinit)
}

internal fun MutableClass.addHelperMethodIfMissing(
    name: String,
    parameterTypes: List<String>,
    returnType: String,
    accessFlags: Int,
    registerCount: Int,
    body: String,
) {
    if (methods.any { it.name == name && it.parameterTypes.map { p -> p.toString() } == parameterTypes && it.returnType == returnType }) {
        return
    }
    val trimmedBody = body.trimIndent()
    val params = parameterTypes.map { ImmutableMethodParameter(it, emptySet(), null) }
    val method = ImmutableMethod(
        type,
        name,
        params,
        returnType,
        accessFlags,
        emptySet(),
        emptySet(),
        MutableMethodImplementation(registerCount),
    ).toMutable()
    method.addInstructions(0, trimmedBody)
    method.retainHelperExceptionMetadata(trimmedBody)
    methods.add(method)
}

private fun MutableMethod.retainHelperExceptionMetadata(body: String) {
    val lines = body.lineSequence().map { it.substringBefore('#').trim() }.toList()
    val catchLines = lines.filter { it.startsWith(".catch") }
    if (catchLines.isEmpty()) return
    val pattern = Regex(
        """^\.catch(?:(all)|\s+(L[^;]+;))\s+\{(:[^\s}]+)\s+\.\.\s+(:[^\s}]+)\}\s+(:[^\s]+)$""",
    )
    val catches = catchLines.map { line ->
        checkNotNull(pattern.matchEntire(line)) { "Unsupported helper catch directive: $line" }
    }
    val labels = mutableMapOf<String, Int>()
    var instructionCount = 0
    lines.forEach { line ->
        when {
            line.isEmpty() -> Unit
            line.startsWith(':') -> {
                check(labels.put(line, instructionCount) == null) { "Duplicate helper label: $line" }
            }
            line.startsWith('.') -> check(
                !line.startsWith(".packed-switch") && !line.startsWith(".sparse-switch") &&
                    !line.startsWith(".array-data"),
            ) { "Catch-bearing helper payloads require full-method assembly" }
            else -> instructionCount++
        }
    }
    val code = checkNotNull(implementation)
    check(instructionCount == code.instructions.size) {
        "Helper catch label/instruction layout drift: $definingClass->$name"
    }
    check(code.tryBlocks.isEmpty()) { "Helper assembler already supplied exception metadata" }
    catches.forEach { match ->
        fun index(group: Int): Int = checkNotNull(labels[match.groupValues[group]]) {
            "Undefined helper catch label: ${match.groupValues[group]}"
        }
        val start = index(3)
        val end = index(4)
        val handler = index(5)
        check(start < end && end <= instructionCount && handler < instructionCount) {
            "Invalid helper exception range: $definingClass->$name"
        }
        val exceptionType = match.groupValues[2].takeIf(String::isNotEmpty)
        code.addCatch(
            exceptionType,
            code.newLabelForIndex(start),
            code.newLabelForIndex(end),
            code.newLabelForIndex(handler),
        )
    }
}

internal const val CUSTOM_INSTRUCTION_PREF_KEY = "pref_key_rambler_custom_instruction"
internal const val USE_DICTIONARY_WORDS_PREF_KEY = "pref_rambler_use_dictionary_words"
internal const val APPEND_ONLY_PREF_KEY = "pref_rambler_append_only"
internal const val CURSOR_EDITING_PREF_KEY = "pref_rambler_cursor_editing_enabled"
internal const val RESELECT_AFTER_EDIT_PREF_KEY = "pref_rambler_reselect_after_edit"
internal const val APP_CONTEXT_PREF_KEY = "pref_rambler_app_context_enabled"
internal const val JETSON_VOICE_SETTINGS_XML = "res/xml/setting_jetson_voice.xml"

internal object RamblerResourceStateStore {
    private val byContext = WeakHashMap<ResourcePatchContext, LinkedHashSet<RamblerFeature>>()

    @Synchronized
    fun markSelected(context: ResourcePatchContext, feature: RamblerFeature) {
        byContext.getOrPut(context) { linkedSetOf() }.add(feature)
    }

    @Synchronized
    fun takeSelected(context: ResourcePatchContext): Set<RamblerFeature> =
        byContext.remove(context) ?: emptySet()
}

internal val ramblerSharedSettingsResourcePatch = resourcePatch(
    description = "Register standalone Rambler Enhancements settings Activity and inject exhaustive Rambler voice typing & Voice typing settings controls.",
) {
    compatibleWith(COMPATIBILITY_GBOARD)

    execute { }

    finalize {
        val selected = RamblerResourceStateStore.takeSelected(this)
        if (selected.isEmpty()) return@finalize

        // 1. Register Activity in AndroidManifest.xml with exported=false + internal action
        //    so Android resolves it inside this exact Gboard/GboardWu package even after Package Rename.
        document("AndroidManifest.xml").use { manifestDoc ->
            registerSettingsActivity(manifestDoc)
        }

        // 2. Add settings strings (English + Traditional Chinese if present)
        val stringsByFolder = mapOf(
            "values" to listOf(
                "rambler_enhancements_entry_title" to "Rambler Enhancements",
                "rambler_enhancements_entry_summary" to
                    "Hex waveform animations, custom instructions, voice dictionary, contacts, and dictation behavior",
                "rambler_enh_custom_instruction_title" to "Custom instructions",
                "rambler_enh_custom_instruction_summary" to
                    "Style rules Rambler applies to everything you dictate, such as spelling, punctuation, or tone",
                "rambler_enh_custom_instruction_hint" to
                    "Added to Rambler instructions. For example: Write every number in digits.",
                "rambler_enh_cat_waveform" to "Waveform",
                "rambler_enh_pref_waveform_title" to "Waveform animation (Hex)",
                "rambler_enh_pref_waveform_summary" to
                    "Listening & processing animations, colors, and Hex tuning",
                "rambler_enh_cat_dictionary_contacts" to "Voice dictionary & contacts",
                "rambler_enh_pref_use_dict_title" to "Use dictionary words",
                "rambler_enh_pref_use_dict_summary" to
                    "Send your saved voice dictionary words with every Rambler request",
                "rambler_enh_pref_manage_dict_title" to "Manage voice dictionary",
                "rambler_enh_pref_manage_dict_summary" to
                    "Add, import (.txt), export, remove, or clear MUSE_STATS biasing words",
                "rambler_enh_pref_contacts_title" to "Contact names sync",
                "rambler_enh_pref_contacts_summary" to
                    "Seed phone contact names into Rambler's voice dictionary",
                "rambler_enh_cat_behavior" to "Dictation behavior",
                "rambler_enh_pref_append_only_title" to "Append-only dictation",
                "rambler_enh_pref_append_only_summary" to
                    "Insert new speech at the cursor without rewriting earlier text; selected text still edits normally",
                "rambler_enh_pref_cursor_editing_title" to "Cursor-aware editing",
                "rambler_enh_pref_cursor_editing_summary" to
                    "Send text after the cursor and scope spoken edits to selected text",
                "rambler_enh_pref_reselect_after_edit_title" to "Re-select after edit",
                "rambler_enh_pref_reselect_after_edit_summary" to
                    "Keep edited text highlighted for chained voice edits (off by default so follow-up dictations append cleanly)",
                "rambler_enh_pref_app_context_title" to "App context",
                "rambler_enh_pref_app_context_summary" to
                    "Share active app name, input type, and field hint so Rambler adapts formatting",
            ),
            "values-zh-rTW" to zhHantSettingsStrings(),
            "values-b+zh+Hant" to zhHantSettingsStrings(),
        )
        for ((folder, pairs) in stringsByFolder) {
            val path = "res/$folder/strings.xml"
            if (folder != "values" && !get(path).isFile) continue
            document(path).use { doc ->
                for ((name, value) in pairs) {
                    ensureString(doc, name, value)
                }
            }
        }

        var injectedCount = 0

        // 3. Inject exhaustive native controls into Gboard's Rambler voice typing screen
        if (get(JETSON_VOICE_SETTINGS_XML).isFile) {
            document(JETSON_VOICE_SETTINGS_XML).use { doc ->
                injectExhaustiveJetsonVoicePreferences(doc, selected)
            }
            injectedCount++
        }

        // 4. Keep unified Rambler Enhancements entry in Voice typing screens
        val voiceXmls = listOf(
            "res/xml/setting_voice_unified.xml",
            "res/xml/setting_voice.xml",
        )
        for (xmlPath in voiceXmls) {
            if (!get(xmlPath).isFile) continue
            document(xmlPath).use { doc ->
                injectSettingsEntryPreference(doc)
            }
            injectedCount++
        }
        if (injectedCount == 0) {
            throw PatchException(
                "Could not locate any Gboard Voice typing settings XML ($JETSON_VOICE_SETTINGS_XML, ${voiceXmls.joinToString()})",
            )
        }
    }
}

private fun ramblerFeatureSelectionResourcePatch(
    feature: RamblerFeature,
    description: String,
) = resourcePatch(description = description) {
    compatibleWith(COMPATIBILITY_GBOARD)
    dependsOn(ramblerSharedSettingsResourcePatch)

    execute {
        RamblerResourceStateStore.markSelected(this, feature)
    }
}

internal val ramblerWaveformResourcePatch = ramblerFeatureSelectionResourcePatch(
    RamblerFeature.WAVEFORM,
    "Expose Hex waveform settings directly inside Gboard's Rambler voice typing screen.",
)
internal val ramblerCustomInstructionsResourcePatch = ramblerFeatureSelectionResourcePatch(
    RamblerFeature.CUSTOM_INSTRUCTIONS,
    "Expose Custom instructions directly inside Gboard's Rambler voice typing screen.",
)
internal val ramblerPersonalDictionaryResourcePatch = ramblerFeatureSelectionResourcePatch(
    RamblerFeature.PERSONAL_DICTIONARY,
    "Expose Use dictionary words toggle and Voice dictionary manager inside Gboard's Rambler voice typing screen.",
)
internal val ramblerContactNamesResourcePatch = ramblerFeatureSelectionResourcePatch(
    RamblerFeature.CONTACT_NAMES,
    "Expose Contact names sync inside Gboard's Rambler voice typing screen.",
)
internal val ramblerCursorEditingResourcePatch = ramblerFeatureSelectionResourcePatch(
    RamblerFeature.CURSOR_EDITING,
    "Expose Append-only dictation and Cursor-aware editing toggles inside Gboard's Rambler voice typing screen.",
)
internal val ramblerAppContextResourcePatch = ramblerFeatureSelectionResourcePatch(
    RamblerFeature.APP_CONTEXT,
    "Expose App context toggle inside Gboard's Rambler voice typing screen.",
)

private fun zhHantSettingsStrings(): List<Pair<String, String>> = listOf(
    "rambler_enhancements_entry_title" to "Rambler 增強功能",
    "rambler_enhancements_entry_summary" to "Hex 聲波動畫、自訂指示、語音辭典、聯絡人與聽寫行為設定",
    "rambler_enh_custom_instruction_title" to "自訂指示",
    "rambler_enh_custom_instruction_summary" to "Rambler 每次語音輸入時套用的風格規則，例如拼字、標點或語氣",
    "rambler_enh_custom_instruction_hint" to "會附加到 Rambler 的預設指示中。例如：所有數字一律使用阿拉伯數字。",
    "rambler_enh_cat_waveform" to "聲波動畫",
    "rambler_enh_pref_waveform_title" to "聲波動畫 (Hex)",
    "rambler_enh_pref_waveform_summary" to "聆聽與處理動畫、配色與 Hex 細節微調",
    "rambler_enh_cat_dictionary_contacts" to "語音辭典與聯絡人",
    "rambler_enh_pref_use_dict_title" to "使用語音辭典詞彙",
    "rambler_enh_pref_use_dict_summary" to "每次 Rambler 語音輸入時一併傳送已儲存的語音辭典詞彙",
    "rambler_enh_pref_manage_dict_title" to "管理語音辭典",
    "rambler_enh_pref_manage_dict_summary" to "新增、匯入 (.txt)、匯出、移除或清除 MUSE_STATS 詞彙",
    "rambler_enh_pref_contacts_title" to "聯絡人名稱同步",
    "rambler_enh_pref_contacts_summary" to "將手機聯絡人名稱同步至 Rambler 語音辭典",
    "rambler_enh_cat_behavior" to "聽寫行為",
    "rambler_enh_pref_append_only_title" to "僅附加模式（不重寫前文）",
    "rambler_enh_pref_append_only_summary" to "在游標處插入新語音而不重寫先前文字；有選取文字時仍可正常語音編輯",
    "rambler_enh_pref_cursor_editing_title" to "游標感知編輯",
    "rambler_enh_pref_cursor_editing_summary" to "傳送游標後的文字，並僅針對選取範圍套用語音編輯",
    "rambler_enh_pref_reselect_after_edit_title" to "編輯後重新選取文字",
    "rambler_enh_pref_reselect_after_edit_summary" to "語音編輯後保持選取以便連續修改（預設關閉，讓後續語音可順利附加）",
    "rambler_enh_pref_app_context_title" to "應用程式情境",
    "rambler_enh_pref_app_context_summary" to "傳送目前應用程式名稱與輸入欄位提示，讓 Rambler 依情境調整格式",
)

internal fun addCustomInstructionPrefIdempotently(screenDoc: Document) {
    val root = screenDoc.documentElement
    if (screenDoc.hasPreferenceKey(CUSTOM_INSTRUCTION_PREF_KEY)) {
        return
    }
    val categoryTag = root.preferredCategoryTag()
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

internal fun injectExhaustiveJetsonVoicePreferences(
    screenDoc: Document,
    selected: Set<RamblerFeature>,
) {
    if (selected.isEmpty()) return
    if (RamblerFeature.CUSTOM_INSTRUCTIONS in selected) {
        addCustomInstructionPrefIdempotently(screenDoc)
    }

    val root = screenDoc.documentElement
    val categoryTag = root.preferredCategoryTag()
    // Place our categories above Gboard's trailing Help & feedback PreferenceCategory.
    val anchor = root.childElements()
        .lastOrNull { el ->
            el.tagName.endsWith("PreferenceCategory") &&
                !el.containsPreferenceKey(CUSTOM_INSTRUCTION_PREF_KEY) &&
                el.androidAttr("key")?.startsWith("rambler_enh_cat_") != true
        }

    fun insertCategory(categoryKey: String, titleRes: String, rows: List<Element>) {
        if (rows.isEmpty() || screenDoc.hasPreferenceKey(categoryKey)) return
        val category = screenDoc.createElement(categoryTag).apply {
            setAndroidAttr("key", categoryKey)
            setAndroidAttr("title", titleRes)
        }
        rows.forEach(category::appendChild)
        if (anchor != null) {
            root.insertBefore(category, anchor)
        } else {
            root.appendChild(category)
        }
    }

    fun sectionIntentPreference(
        key: String,
        titleRes: String,
        summaryRes: String,
        section: String,
    ): Element? {
        if (screenDoc.hasPreferenceKey(key)) return null
        val pref = screenDoc.createElement("Preference").apply {
            setAndroidAttr("key", key)
            setAndroidAttr("persistent", "false")
            setAndroidAttr("title", titleRes)
            setAndroidAttr("summary", summaryRes)
        }
        val intent = screenDoc.createElement("intent").apply {
            setAndroidAttr("action", OPEN_SETTINGS_ACTION)
        }
        val extra = screenDoc.createElement("extra").apply {
            setAndroidAttr("name", "section")
            setAndroidAttr("value", section)
        }
        intent.appendChild(extra)
        pref.appendChild(intent)
        return pref
    }

    fun switchPreference(
        key: String,
        defaultValue: Boolean,
        titleRes: String,
        summaryRes: String,
    ): Element? {
        if (screenDoc.hasPreferenceKey(key)) return null
        return screenDoc.createElement("SwitchPreferenceCompat").apply {
            setAndroidAttr("key", key)
            setAndroidAttr("persistent", "true")
            setAndroidAttr("defaultValue", defaultValue.toString())
            setAndroidAttr("title", titleRes)
            setAndroidAttr("summary", summaryRes)
        }
    }

    if (RamblerFeature.WAVEFORM in selected) {
        val rows = listOfNotNull(
            sectionIntentPreference(
                key = "rambler_enh_waveform_entry",
                titleRes = "@string/rambler_enh_pref_waveform_title",
                summaryRes = "@string/rambler_enh_pref_waveform_summary",
                section = "waveform",
            ),
        )
        insertCategory("rambler_enh_cat_waveform", "@string/rambler_enh_cat_waveform", rows)
    }

    if (RamblerFeature.PERSONAL_DICTIONARY in selected || RamblerFeature.CONTACT_NAMES in selected) {
        val rows = mutableListOf<Element>()
        if (RamblerFeature.PERSONAL_DICTIONARY in selected) {
            switchPreference(
                key = USE_DICTIONARY_WORDS_PREF_KEY,
                defaultValue = true,
                titleRes = "@string/rambler_enh_pref_use_dict_title",
                summaryRes = "@string/rambler_enh_pref_use_dict_summary",
            )?.let(rows::add)
            sectionIntentPreference(
                key = "rambler_enh_dictionary_entry",
                titleRes = "@string/rambler_enh_pref_manage_dict_title",
                summaryRes = "@string/rambler_enh_pref_manage_dict_summary",
                section = "dictionary",
            )?.let(rows::add)
        }
        if (RamblerFeature.CONTACT_NAMES in selected) {
            sectionIntentPreference(
                key = "rambler_enh_contacts_entry",
                titleRes = "@string/rambler_enh_pref_contacts_title",
                summaryRes = "@string/rambler_enh_pref_contacts_summary",
                section = "contacts",
            )?.let(rows::add)
        }
        insertCategory(
            "rambler_enh_cat_dictionary_contacts",
            "@string/rambler_enh_cat_dictionary_contacts",
            rows,
        )
    }

    if (RamblerFeature.CURSOR_EDITING in selected || RamblerFeature.APP_CONTEXT in selected) {
        val rows = mutableListOf<Element>()
        if (RamblerFeature.CURSOR_EDITING in selected) {
            switchPreference(
                key = APPEND_ONLY_PREF_KEY,
                defaultValue = false,
                titleRes = "@string/rambler_enh_pref_append_only_title",
                summaryRes = "@string/rambler_enh_pref_append_only_summary",
            )?.let(rows::add)
            switchPreference(
                key = CURSOR_EDITING_PREF_KEY,
                defaultValue = true,
                titleRes = "@string/rambler_enh_pref_cursor_editing_title",
                summaryRes = "@string/rambler_enh_pref_cursor_editing_summary",
            )?.let(rows::add)
            switchPreference(
                key = RESELECT_AFTER_EDIT_PREF_KEY,
                defaultValue = false,
                titleRes = "@string/rambler_enh_pref_reselect_after_edit_title",
                summaryRes = "@string/rambler_enh_pref_reselect_after_edit_summary",
            )?.let(rows::add)
        }
        if (RamblerFeature.APP_CONTEXT in selected) {
            switchPreference(
                key = APP_CONTEXT_PREF_KEY,
                defaultValue = true,
                titleRes = "@string/rambler_enh_pref_app_context_title",
                summaryRes = "@string/rambler_enh_pref_app_context_summary",
            )?.let(rows::add)
        }
        insertCategory("rambler_enh_cat_behavior", "@string/rambler_enh_cat_behavior", rows)
    }
}

private fun Document.hasPreferenceKey(key: String): Boolean {
    val all = getElementsByTagName("*")
    for (i in 0 until all.length) {
        val el = all.item(i) as? Element ?: continue
        if (el.androidAttr("key") == key) {
            return true
        }
    }
    return false
}

private fun Element.containsPreferenceKey(key: String): Boolean {
    if (androidAttr("key") == key) return true
    val all = getElementsByTagName("*")
    for (i in 0 until all.length) {
        val el = all.item(i) as? Element ?: continue
        if (el.androidAttr("key") == key) {
            return true
        }
    }
    return false
}

private fun Element.preferredCategoryTag(): String =
    childElements()
        .firstOrNull { it.tagName.endsWith("PreferenceCategory") }
        ?.tagName
        ?: "androidx.preference.PreferenceCategory"

internal fun registerSettingsActivity(manifestDoc: Document) {
    val manifest = manifestDoc.documentElement
    val application = manifest.childElements("application").firstOrNull()
        ?: throw PatchException("AndroidManifest.xml is missing <application>")

    val existing = application.childElements("activity").firstOrNull {
        it.androidAttr("name") == SETTINGS_ACTIVITY_CLASS
    }
    val activity = existing ?: manifestDoc.createElement("activity").also(application::appendChild)
    activity.setAndroidAttr("name", SETTINGS_ACTIVITY_CLASS)
    activity.setAndroidAttr("exported", "false")
    activity.setAndroidAttr("theme", "@android:style/Theme.DeviceDefault.Settings")
    activity.setAndroidAttr("label", "@string/rambler_enhancements_entry_title")

    val filter = activity.childElements("intent-filter").firstOrNull { f ->
        f.childElements("action").any { it.androidAttr("name") == OPEN_SETTINGS_ACTION }
    } ?: manifestDoc.createElement("intent-filter").also(activity::appendChild)

    if (filter.childElements("action").none { it.androidAttr("name") == OPEN_SETTINGS_ACTION }) {
        filter.appendChild(
            manifestDoc.createElement("action").apply {
                setAndroidAttr("name", OPEN_SETTINGS_ACTION)
            },
        )
    }
    if (filter.childElements("category").none { it.androidAttr("name") == "android.intent.category.DEFAULT" }) {
        filter.appendChild(
            manifestDoc.createElement("category").apply {
                setAndroidAttr("name", "android.intent.category.DEFAULT")
            },
        )
    }
}

internal fun injectSettingsEntryPreference(screenDoc: Document) {
    val root = screenDoc.documentElement
    val allNodes = screenDoc.getElementsByTagName("*")
    for (i in 0 until allNodes.length) {
        val el = allNodes.item(i) as? Element ?: continue
        if (el.androidAttr("key") == "rambler_enhancements_entry") {
            return
        }
    }

    val categoryTag = root.childElements()
        .firstOrNull { it.tagName.endsWith("PreferenceCategory") }
        ?.tagName
        ?: "PreferenceCategory"

    val category = screenDoc.createElement(categoryTag)
    val pref = screenDoc.createElement("Preference").apply {
        setAndroidAttr("key", "rambler_enhancements_entry")
        setAndroidAttr("persistent", "false")
        setAndroidAttr("title", "@string/rambler_enhancements_entry_title")
        setAndroidAttr("summary", "@string/rambler_enhancements_entry_summary")
    }
    val intent = screenDoc.createElement("intent").apply {
        setAndroidAttr("action", OPEN_SETTINGS_ACTION)
    }
    pref.appendChild(intent)
    category.appendChild(pref)
    root.appendChild(category)
}

internal fun ensureString(stringsDoc: Document, name: String, value: String) {
    val root = stringsDoc.documentElement
    if (root.childElements("string").any { it.getAttribute("name") == name }) {
        return
    }
    root.appendChild(
        stringsDoc.createElement("string").apply {
            setAttribute("name", name)
            textContent = value
        },
    )
}

internal fun Element.childElements(tagName: String? = null): List<Element> {
    val result = mutableListOf<Element>()
    val children = childNodes
    for (i in 0 until children.length) {
        val child = children.item(i) as? Element ?: continue
        if (tagName == null || child.tagName == tagName) {
            result.add(child)
        }
    }
    return result
}

internal fun Element.androidAttr(localName: String): String? {
    val ns = getAttributeNS(ANDROID_NS, localName)
    if (ns.isNotBlank()) return ns
    return getAttribute("android:$localName").takeIf { it.isNotBlank() }
}

internal fun Element.setAndroidAttr(localName: String, value: String) {
    setAttributeNS(ANDROID_NS, "android:$localName", value)
}
