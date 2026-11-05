package org.ungoogled.patches.coloros

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Android's Context classes an app's own classes (and the libraries it bundles) can extend. */
private val FRAMEWORK_CONTEXTS = setOf(
    "Landroid/content/Context;", "Landroid/content/ContextWrapper;", "Landroid/view/ContextThemeWrapper;",
    "Landroid/app/Activity;", "Landroid/app/Application;", "Landroid/app/Service;", "Landroid/app/IntentService;",
    "Landroid/app/ListActivity;", "Landroid/app/ExpandableListActivity;", "Landroid/app/AliasActivity;",
    "Landroid/app/TabActivity;", "Landroid/app/NativeActivity;", "Landroid/preference/PreferenceActivity;",
    "Landroid/app/job/JobService;", "Landroid/service/quicksettings/TileService;",
    "Landroid/service/notification/NotificationListenerService;", "Landroid/accessibilityservice/AccessibilityService;",
    "Landroid/inputmethodservice/InputMethodService;", "Landroid/service/dreams/DreamService;",
    "Landroid/service/wallpaper/WallpaperService;", "Landroid/widget/RemoteViewsService;",
)

/** registerReceiver, with or without flags -> the runtime's method of the same name (the Context first). */
private val SHAPES = mapOf(
    "(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;I)Landroid/content/Intent;" to
        "(Landroid/content/Context;Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;I)Landroid/content/Intent;",
    "(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;Ljava/lang/String;Landroid/os/Handler;I)Landroid/content/Intent;" to
        "(Landroid/content/Context;Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;Ljava/lang/String;Landroid/os/Handler;I)Landroid/content/Intent;",
    "(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;" to
        "(Landroid/content/Context;Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;",
    "(Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;Ljava/lang/String;Landroid/os/Handler;)Landroid/content/Intent;" to
        "(Landroid/content/Context;Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;Ljava/lang/String;Landroid/os/Handler;)Landroid/content/Intent;",
)

private val VIRTUAL = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)

/**
 * ColorOS's apps are system apps there, which Android lets register a broadcast receiver at run time without saying
 * whether other apps may send to it. An installed app targeting Android 14+ must say so, or registerReceiver throws a
 * SecurityException ("One of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED should be specified") for any broadcast that
 * isn't the system's -- also with flags that name neither (OnePlus Photos passes 0 and dies on it at start). COUI's
 * status-bar "tap to scroll up" receiver (com.oplus.clicktop) and OPPO's NearX Track preferences have such calls too.
 * Sends every registerReceiver call in the app (not in our extensions; on any Context, also the app's own
 * Activity/Service/Application subclasses) to [runtime]'s registerReceiver(Context, ...) (runtime/ColorOS.java), which
 * registers a receiver whose flags say neither as exported, as Android did before Android 14 and as ColorOS does for
 * its system apps; flags that say either are kept. Returns the calls moved.
 */
internal fun BytecodePatchContext.exportDynamicReceivers(runtime: String): Int =
    redirectContextCalls(runtime, SHAPES.mapKeys { (shape, _) -> "registerReceiver$shape" }.mapValues { (_, to) -> "registerReceiver$to" })

/**
 * Sends every call in the app (not in our extensions) to one of [shapes]' keys ("name(params)return" of a Context
 * method, on any Context: Android's own and the app's Activity/Service/Application subclasses) to [runtime]'s method
 * of the value's "name(params)return", which takes the Context first. Same registers, only the target changes.
 * Returns the calls moved.
 */
internal fun BytecodePatchContext.redirectContextCalls(runtime: String, shapes: Map<String, String>): Int {
    val contexts = mutableMapOf<String, Boolean>()
    fun isContext(type: String): Boolean = contexts.getOrPut(type) {
        if (type in FRAMEWORK_CONTEXTS) return@getOrPut true
        val superclass = classDefByOrNull(type)?.superclass ?: return@getOrPut false
        isContext(superclass)
    }
    fun MethodReference.shape() = "$name(${parameterTypes.joinToString("")})$returnType"
    fun Any.match(): MethodReference? {
        if (this !is ReferenceInstruction || opcode !in VIRTUAL) return null
        val r = reference as? MethodReference ?: return null
        return if (r.shape() in shapes && isContext(r.definingClass)) r else null
    }

    val sites = mutableListOf<Pair<String, Method>>()
    classDefForEach { c ->
        if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) {
            if (m.implementation?.instructions?.any { it.match() != null } == true) sites += c.type to m
        }
    }
    var moved = 0
    for ((type, method) in sites) {
        val mutable = mutableClassDefBy(type).methods.first {
            it.name == method.name && it.parameterTypes == method.parameterTypes && it.returnType == method.returnType
        }
        val instructions = mutable.implementation!!.instructions
        for (index in instructions.indices) {
            val insn = instructions[index]
            val r = insn.match() ?: continue
            val to = "$runtime->" + shapes.getValue(r.shape())
            val call = when (insn) {
                is FiveRegisterInstruction ->
                    listOf(insn.registerC, insn.registerD, insn.registerE, insn.registerF, insn.registerG)
                        .take(insn.registerCount).joinToString(", ", "invoke-static { ", " }, $to") { "v$it" }
                is RegisterRangeInstruction ->
                    "invoke-static/range { v${insn.startRegister} .. v${insn.startRegister + insn.registerCount - 1} }, $to"
                else -> throw PatchException("unexpected ${insn.opcode} in $type.${method.name}")
            }
            mutable.replaceInstruction(index, call)
            moved++
        }
    }
    return moved
}
