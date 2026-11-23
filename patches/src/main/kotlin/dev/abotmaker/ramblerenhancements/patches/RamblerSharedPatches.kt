package dev.abotmaker.ramblerenhancements.patches

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.util.WeakHashMap

internal const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
internal const val GBOARD_WU_PACKAGE = "dev.jason.com.google.android.inputmethod.latin"
internal const val VERIFIED_GBOARD_VERSION = "18.5.4.993608717-release-arm64-v8a"
internal val COMPATIBILITY_GBOARD = Compatibility(
    name = "Gboard",
    packageName = GBOARD_PACKAGE,
    appIconColor = 0x1A73E8,
    targets = listOf(
        AppTarget(
            version = null,
            description = "Verified on Gboard $VERIFIED_GBOARD_VERSION (current GboardWu target); newer/other versions are best-effort and unverified.",
        ),
    ),
)
internal val COMPATIBILITY_GBOARD_WU = Compatibility(
    name = "Gboard (GboardWu)",
    packageName = GBOARD_WU_PACKAGE,
    appIconColor = 0x1A73E8,
    targets = listOf(
        AppTarget(
            version = null,
            description = "Also supports patching an already-renamed GboardWu APK.",
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
    compatibleWith(COMPATIBILITY_GBOARD, COMPATIBILITY_GBOARD_WU)
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

        if (RamblerFeature.CUSTOM_INSTRUCTIONS in state.selectedFeatures) {
            val agentic = state.agenticTargets ?: RamblerDynamicResolver.resolveAgenticExtension(this)
                .also { state.agenticTargets = it }
            if (state.prefsTargets == null) {
                state.prefsTargets = RamblerDynamicResolver.resolvePrefsTargets(
                    this,
                    agentic.extensionClass,
                )
            }
        }

        rewriteDynamicConfigClinit(state)
    }
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
    val clinit = configClass.methods.firstOrNull { it.name == "<clinit>" }
        ?: throw PatchException("$DYNAMIC_CONFIG_TYPE is missing <clinit>")

    val existingCount = clinit.implementation?.instructions?.size ?: 0
    if (existingCount > 0) {
        clinit.removeInstructions(0, existingCount)
    }

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

    sb.appendLine("return-void")
    clinit.addInstructions(0, sb.toString().trimIndent())
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
    method.addInstructions(0, body)
    methods.add(method)
}

internal val ramblerSharedSettingsResourcePatch = resourcePatch(
    description = "Register standalone Rambler Enhancements settings Activity and inject Voice typing settings entry.",
) {
    compatibleWith(COMPATIBILITY_GBOARD, COMPATIBILITY_GBOARD_WU)

    execute {
        // 1. Register Activity in AndroidManifest.xml with exported=false + internal action
        //    so Android resolves it inside this exact Gboard/GboardWu package even after Package Rename.
        document("AndroidManifest.xml").use { manifestDoc ->
            registerSettingsActivity(manifestDoc)
        }

        // 2. Add settings entry strings (English + Traditional Chinese if present)
        val entryStrings = mapOf(
            "values" to (
                "Rambler Enhancements" to
                    "Hex waveform animations, custom instructions, voice dictionary, contacts, and dictation behavior"
            ),
            "values-zh-rTW" to (
                "Rambler 增強功能" to
                    "Hex 聲波動畫、自訂指示、語音辭典、聯絡人與聽寫行為設定"
            ),
            "values-b+zh+Hant" to (
                "Rambler 增強功能" to
                    "Hex 聲波動畫、自訂指示、語音辭典、聯絡人與聽寫行為設定"
            ),
        )
        for ((folder, pair) in entryStrings) {
            val path = "res/$folder/strings.xml"
            if (folder != "values" && !get(path).isFile) continue
            document(path).use { doc ->
                ensureString(doc, "rambler_enhancements_entry_title", pair.first)
                ensureString(doc, "rambler_enhancements_entry_summary", pair.second)
            }
        }

        // 3. Inject entry Preference into every present Voice typing / Rambler settings screen
        val candidateXmls = listOf(
            "res/xml/setting_jetson_voice.xml",
            "res/xml/setting_voice_unified.xml",
            "res/xml/setting_voice.xml",
        )
        var injectedCount = 0
        for (xmlPath in candidateXmls) {
            if (!get(xmlPath).isFile) continue
            document(xmlPath).use { doc ->
                injectSettingsEntryPreference(doc)
            }
            injectedCount++
        }
        if (injectedCount == 0) {
            throw PatchException(
                "Could not locate any Gboard Voice typing settings XML (${candidateXmls.joinToString()})",
            )
        }
    }
}

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
