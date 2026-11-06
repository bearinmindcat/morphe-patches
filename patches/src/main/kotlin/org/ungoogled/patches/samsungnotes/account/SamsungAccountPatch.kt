package org.ungoogled.patches.samsungnotes.account

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.ungoogled.patches.samsungnotes.SamsungNotesConstants.COMPATIBILITY_SAMSUNG_NOTES
import org.ungoogled.patches.samsungnotes.misc.SAMSUNG_NOTES_EXTENSION
import org.ungoogled.patches.samsungnotes.misc.enableNonOneUiPatch
import org.w3c.dom.Element

private const val ACCOUNT = "Lorg/ungoogled/samsungnotes/account/SamsungAccount;"
internal const val SIGN_IN_ACTIVITY = "org.ungoogled.samsungnotes.account.SignInActivity"

/** The Samsung Account system app, which Notes' account code talks to. */
private const val ACCOUNT_APP = "com.osp.app.signin"

/** The Samsung Account app's screens Notes opens; without the app they now open Notes' own sign-in screen. */
private val ACCOUNT_ACTIONS = listOf(
    "com.msc.action.samsungaccount.SIGNIN_POPUP",
    "com.msc.action.samsungaccount.REQUEST_ACCESSTOKEN",
    "com.msc.action.samsungaccount.REQUEST_CHECKLIST_VALIDATION",
)

private val CONST_STRING = setOf(Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO)
private fun Instruction.constString() =
    if (opcode in CONST_STRING) ((this as ReferenceInstruction).reference as StringReference).string else null
private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun MethodReference.signature() = "$name(${parameterTypes.joinToString("")})$returnType"

/** CommonUtils.hasSamsungAccountPackage(Context): picks Notes' account mode (app = full, none = unfinished "web"). */
private object HasAccountAppFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(string("hasSamsungAccountPackage# NameNotFoundException:")),
)

/** The signed-in account's name, from Android's account list (com.osp.app.signin accounts) or the app's provider. */
private object AccountNameFingerprint : Fingerprint(
    returnType = "Ljava/lang/String;",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(string("[SA] getSamsungAccount() : manager is null. please check the APK signing!")),
)

/** Binds the Samsung Account app's request service (com.msc.sa.service.RequestService). */
private object AccountServiceBindFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(string("com.msc.action.samsungaccount.REQUEST_SERVICE")),
)

/** Unbinds it again. */
private object AccountServiceUnbindFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    filters = listOf(string("[SA] disConnectSamsungAccountService() : fail to unbind = ")),
)

/** Why this phone may not sync (DeviceUtils.getSyncRestrictedDeviceError): first "no account package". */
private object SyncRestrictedFingerprint : Fingerprint(
    returnType = "I",
    parameters = listOf("Landroid/content/Context;"),
    filters = listOf(string("getDeviceSyncError : false <- No account package!")),
)

/** PermissionHelper.isPermissionGrantedWithoutNotice: checks GET_ACCOUNTS and READ_PHONE_STATE only off Samsung. */
private object PermissionGrantedFingerprint : Fingerprint(
    returnType = "Z",
    filters = listOf(string("isPermissionGrantedWithoutNotice : ")),
)

/** Declares the sign-in screen and adds "Samsung account" to Notes' sync settings. */
private val signInResourcesPatch = resourcePatch(
    description = "Declares the Samsung account sign-in screen and its row in the sync settings.",
) {
    execute {
        val packageName = document("AndroidManifest.xml").use { manifest ->
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val activity = manifest.createElement("activity")
            activity.setAttribute("android:name", SIGN_IN_ACTIVITY)
            activity.setAttribute("android:exported", "false")
            activity.setAttribute("android:theme", "@android:style/Theme.DeviceDefault.DayNight")
            // Samsung's sign-in page keeps its state when the phone turns.
            activity.setAttribute("android:configChanges", "orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden")
            activity.setAttribute("android:windowSoftInputMode", "adjustResize")
            val filter = manifest.createElement("intent-filter")
            for (action in ACCOUNT_ACTIONS) {
                filter.appendChild(manifest.createElement("action").apply { setAttribute("android:name", action) })
            }
            filter.appendChild(manifest.createElement("category").apply { setAttribute("android:name", "android.intent.category.DEFAULT") })
            activity.appendChild(filter)
            application.appendChild(activity)
            manifest.documentElement.getAttribute("package")
        }
        if (packageName.isNullOrEmpty()) throw PatchException("manifest has no package")

        document("res/xml/settings_sync_with_samsung_account_preference.xml").use { settings ->
            val all = settings.getElementsByTagName("*")
            var category: Element? = null
            for (i in 0 until all.length) {
                val e = all.item(i) as Element
                if (e.getAttribute("android:key") == "settings_sync_with_samsung_account_contents_category") category = e
            }
            category ?: throw PatchException("sync settings have no contents category")
            val row = settings.createElement("androidx.preference.Preference")
            row.setAttribute("android:key", "ungoogled_samsung_account")
            row.setAttribute("android:title", "Samsung account")
            row.setAttribute("android:summary", "Account and sign out")
            row.setAttribute("android:persistent", "false")
            // androidx.preference opens a row's <intent> when it is tapped.
            val intent = settings.createElement("intent")
            intent.setAttribute("android:targetPackage", packageName)
            intent.setAttribute("android:targetClass", SIGN_IN_ACTIVITY)
            row.appendChild(intent)
            category.appendChild(row)
        }
    }
}

/** Methods that build an Intent for one of the Samsung Account app's screens (read-only pass). */
private fun BytecodePatchContext.accountScreenMethods(): List<Method> {
    val found = mutableListOf<Method>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        c.methods.filterTo(found) { m ->
            val insns = m.implementation?.instructions ?: return@filterTo false
            insns.any { it.constString() in ACCOUNT_ACTIONS } && insns.any { it.constString() == ACCOUNT_APP }
        }
    }
    return found
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
    }

/** Replaces each const-string "com.osp.app.signin" in this method with SamsungAccount.accountAppPackage(). */
private fun MutableMethod.redirectAccountPackage(): Int {
    var count = 0
    var index = 0
    while (index < implementation!!.instructions.size) {
        val insn = implementation!!.instructions[index]
        if (insn.constString() == ACCOUNT_APP) {
            val register = (insn as OneRegisterInstruction).registerA
            replaceInstruction(index, "invoke-static { }, $ACCOUNT->accountAppPackage()Ljava/lang/String;")
            addInstruction(index + 1, "move-result-object v$register")
            count++
            index++
        }
        index++
    }
    return count
}

/** In [method], replaces each `invoke-virtual Context.<signature>` with a static call into SamsungAccount. */
private fun MutableMethod.redirectContextCall(signature: String, replacement: String): Int {
    val instructions = implementation!!.instructions
    var count = 0
    for (index in instructions.indices) {
        val insn = instructions[index]
        val ref = insn.methodRef() ?: continue
        if (insn.opcode != Opcode.INVOKE_VIRTUAL || ref.signature() != signature) continue
        val i = insn as FiveRegisterInstruction
        val registers = listOf(i.registerC, i.registerD, i.registerE, i.registerF, i.registerG).take(i.registerCount)
        replaceInstruction(index, "invoke-static { ${registers.joinToString { "v$it" }} }, $replacement")
        count++
    }
    return count
}

@Suppress("unused")
val samsungAccountPatch = bytecodePatch(
    name = "Samsung account sign-in",
    description = "Signs in to your Samsung account on Samsung's web page to sync with Samsung Cloud (experimental).",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_NOTES)
    dependsOn(enableNonOneUiPatch, signInResourcesPatch)
    extendWith(SAMSUNG_NOTES_EXTENSION)

    execute {
        val log = java.util.logging.Logger.getLogger("SamsungNotes")

        // Notes' full Samsung account mode; its "web" mode for phones without the app is unfinished in this build.
        HasAccountAppFingerprint.method.addInstructions(
            0,
            """
                invoke-static { p0 }, $ACCOUNT->hasAccountApp(Landroid/content/Context;)Z
                move-result v0
                return v0
            """,
        )

        // The account name: from the web sign-in when the app is missing.
        AccountNameFingerprint.method.apply {
            if (implementation!!.registerCount < 2) throw PatchException("account name lookup has no local register")
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p0 }, $ACCOUNT->web(Landroid/content/Context;)Z
                    move-result v0
                    if-eqz v0, :account_app
                    invoke-static { p0 }, $ACCOUNT->accountName(Landroid/content/Context;)Ljava/lang/String;
                    move-result-object v0
                    return-object v0
                """,
                ExternalLabel("account_app", implementation!!.instructions.first()),
            )
        }

        // The account service: answered in-process when the app is missing.
        val bound = AccountServiceBindFingerprint.method.redirectContextCall(
            "bindService(Landroid/content/Intent;Landroid/content/ServiceConnection;I)Z",
            "$ACCOUNT->bindService(Landroid/content/Context;Landroid/content/Intent;Landroid/content/ServiceConnection;I)Z",
        )
        val unbound = AccountServiceUnbindFingerprint.method.redirectContextCall(
            "unbindService(Landroid/content/ServiceConnection;)V",
            "$ACCOUNT->unbindService(Landroid/content/Context;Landroid/content/ServiceConnection;)V",
        )
        if (bound != 1 || unbound != 1) throw PatchException("account service bind/unbind not found ($bound/$unbound)")

        // The app's sign-in, token and account-check screens: Notes' own sign-in screen when the app is missing.
        val screens = accountScreenMethods()
        val redirected = screens.sumOf { mutable(it).redirectAccountPackage() }
        // Measured against 4.4.48.15: 4 (sign-in and token intents in AccountHelper, the account check before "Sync
        // now" and before an import).
        if (redirected < 3) throw PatchException("only $redirected Samsung account screens found")

        // "May this phone sync?": its account half (DeviceUtils.isSupportedAccount, which also wants a Samsung phone)
        // answers yes when the web sign-in provides the account. Galaxy AI and note locks keep their own check.
        SyncRestrictedFingerprint.method.apply {
            val type = SyncRestrictedFingerprint.classDef.type
            val instructions = implementation!!.instructions
            val index = instructions.indexOfFirst {
                it.opcode == Opcode.INVOKE_VIRTUAL && it.methodRef()?.let { r ->
                    r.definingClass == type && r.signature().endsWith("(Landroid/content/Context;)Z")
                } == true
            }
            if (index < 0) throw PatchException("sync check has no account check")
            val i = instructions[index] as FiveRegisterInstruction
            replaceInstruction(index, "invoke-static { v${i.registerC}, v${i.registerD} }, $ACCOUNT->syncSupported(Ljava/lang/Object;Landroid/content/Context;)Z")
        }

        // Off Samsung, Notes checks GET_ACCOUNTS and READ_PHONE_STATE before syncing; its manifest declares neither,
        // so they can never be granted. They are skipped as on Samsung phones.
        PermissionGrantedFingerprint.method.apply {
            val instructions = implementation!!.instructions
            val accounts = instructions.indexOfFirst { it.constString() == "android.permission.GET_ACCOUNTS" }
            val check = (accounts - 1 downTo 0).firstOrNull {
                instructions[it].opcode == Opcode.INVOKE_STATIC && instructions[it].methodRef()?.let { r ->
                    r.returnType == "Z" && r.parameterTypes.isEmpty()
                } == true
            } ?: throw PatchException("permission check has no Samsung phone check")
            val result = instructions[check + 1]
            if (result.opcode != Opcode.MOVE_RESULT) throw PatchException("Samsung phone check result not found")
            replaceInstruction(check + 1, "const/4 v${(result as OneRegisterInstruction).registerA}, 0x0")
        }

        log.info("Samsung account sign-in: $redirected account screens, ${screens.size} methods")
    }
}
