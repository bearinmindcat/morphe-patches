package org.ungoogled.patches.gmail

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.ungoogled.patches.gmail.GmailConstants.COMPATIBILITY_GMAIL

/** GenAiEligibilityInfo's toString: its field names, in constructor order. */
private const val ELIGIBILITY_FIELDS =
    "userTypeForFeedback;aiInboxLifecycleState;aiSearchOverviewLifecycleState;adHocSchedulingV2Eligibility;" +
        "addToCalendarEligibility;aiInboxEligibility;aiSearchOverviewEligibility;contextualSmartReplyEligibility;" +
        "helpMeWriteEligibility;proofreadEligibility;schedulingIntentDetectionEligibility;sidekickEligibility;" +
        "summarizationEligibility;voiceHmwEligibility;voiceLiveEligibility"

/**
 * The Gemini features, by their eligibility's name: AI Inbox, AI search overviews, Gemini replies, Help me write,
 * Polish and Proofread, the Gemini side panel, summaries and the two Gemini voice features. Scheduling and "add to
 * calendar" detection are left as they are.
 */
private val AI_FEATURES = listOf(
    "aiInboxEligibility", "aiSearchOverviewEligibility", "contextualSmartReplyEligibility", "helpMeWriteEligibility",
    "proofreadEligibility", "sidekickEligibility", "summarizationEligibility", "voiceHmwEligibility", "voiceLiveEligibility",
)

private const val SIDEKICK_CHECKER = "com/google/android/apps/gmail/features/sidekick/impl/SidekickEligibilityCheckerImpl"
private const val SEARCH_ENTRY_POINTS = "Action view for open_search_sidekick_entry_point should not be null"
private const val CONVERSATION_FRAGMENT = "com/android/mail/ui/AbstractConversationViewFragment"
private const val ACTION_BAR_CONTROLLER = "com/android/mail/ui/ActionBarController"
private const val MEET_GEMINI_BUTTON = "sidekick_entry_button_clicked"
private const val ACCOUNT = "Lcom/google/android/apps/gmail/libraries/accounts/android/GmailAndroidAccount;"
private const val LISTENABLE_FUTURE = "Lcom/google/common/util/concurrent/ListenableFuture;"
private const val MENU_ITEM = "Landroid/view/MenuItem;"

private fun MethodReference.signature() = parameterTypes.joinToString("", "(", ")") + returnType

/** The one class whose static initializer loads [string] (a class's own logger name). */
private fun BytecodePatchContext.classLogging(string: String): ClassDef =
    classesLogging(string).singleOrNull() ?: throw PatchException("expected one class logging as $string, found ${classesLogging(string).size}")

/**
 * Gemini's buttons, panels and cards are shown only to accounts Gmail's server marks eligible, so the patch
 * answers every eligibility check with "not eligible": Gmail then leaves the entry points out, as it does for
 * accounts without Gemini. A plain "not eligible" (not "turned off in settings") also keeps the "turn on smart
 * features" prompts away.
 */
@Suppress("unused")
val gmailHideAiPatch = bytecodePatch(
    name = "Hide AI",
    description = "Hides Gemini, Help me write, Polish, summaries, the AI inbox and Gemini replies.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_GMAIL)

    execute {
        val anchors = methodsUsing(setOf(ELIGIBILITY_FIELDS, SEARCH_ENTRY_POINTS, MEET_GEMINI_BUTTON))

        // 1. GenAiEligibilityInfo, the per-account answer every Gemini feature reads: its constructor stores
        //    the static "not eligible" value for each Gemini feature, whatever the server said.
        val info = anchors.single(ELIGIBILITY_FIELDS, "GenAI eligibility info").classDef
        val names = ELIGIBILITY_FIELDS.split(';')
        val constructor = info.methods.singleOrNull { it.name == "<init>" && it.parameterTypes.size == names.size }
            ?: throw PatchException("GenAI eligibility info no longer has a ${names.size}-value constructor")
        val eligibility = constructor.parameterTypes[names.indexOf("helpMeWriteEligibility")].toString()
        val ineligible = classDefBy(eligibility).fields.filter {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.type == eligibility
        }.singleOrNull() ?: throw PatchException("$eligibility no longer has one static \"not eligible\" value")
        mutableClassDefBy(info.type).methods.single { it.name == "<init>" && it.parameterTypes.size == names.size }.apply {
            val smali = AI_FEATURES.joinToString("\n") { name ->
                val index = names.indexOf(name)
                if (parameterTypes[index].toString() != eligibility) throw PatchException("$name is no longer a feature eligibility")
                "sget-object p${index + 1}, $eligibility->${ineligible.name}:$eligibility"
            }
            addInstructions(0, smali)
        }

        // 2. The Gemini side panel's own check (SidekickEligibilityCheckerImpl): it answers "not eligible" at
        //    once for accounts that are not Google accounts; now it does so for every account.
        val checker = classLogging(SIDEKICK_CHECKER)
        val sidekickFuture = checker.methods.singleOrNull {
            AccessFlags.PRIVATE.isSet(it.accessFlags) && it.returnType == LISTENABLE_FUTURE &&
                it.parameterTypes.map(CharSequence::toString) == listOf(ACCOUNT)
        } ?: throw PatchException("Gemini side panel check no longer has its per-account future")
        mutableClassDefBy(checker.type).methods.single { it.name == sidekickFuture.name && it.returnType == LISTENABLE_FUTURE }.apply {
            val instructions = implementation!!.instructions.toList()
            val isGoogle = instructions.indexOfFirst { insn ->
                insn.opcode == Opcode.INVOKE_STATIC && ((insn as ReferenceInstruction).reference as MethodReference).signature() == "(${ACCOUNT}Z)Z"
            }
            val move = instructions.getOrNull(isGoogle + 1)
            val branch = instructions.getOrNull(isGoogle + 2)
            if (isGoogle < 0 || move?.opcode != Opcode.MOVE_RESULT || branch?.opcode != Opcode.IF_NEZ) {
                throw PatchException("Gemini side panel check no longer starts with its Google account test")
            }
            replaceInstruction(isGoogle + 1, "const/4 v${(move as OneRegisterInstruction).registerA}, 0x0")
        }

        // 3. The Gemini button in the search bar: its state (shown, and two variants) comes from one language and
        //    eligibility test, called three times by the state's constructor; that test now says no.
        val entryPoints = anchors[SEARCH_ENTRY_POINTS].orEmpty().ifEmpty { throw PatchException("search bar entry points not found") }
        val stateTypes = entryPoints.flatMap { found ->
            found.method.implementation!!.instructions.mapNotNull { insn ->
                ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf { insn.opcode == Opcode.IGET_BOOLEAN }?.definingClass
            }
        }.toSet().filter { type ->
            classDefByOrNull(type)?.methods?.any { it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("Z", "Z", "Z", "Z") } == true
        }
        val state = stateTypes.singleOrNull() ?: throw PatchException("search bar Gemini state not found: $stateTypes")
        val tests = mutableSetOf<String>()
        classDefForEach { c ->
            if (c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
            for (m in c.methods) {
                val code = m.implementation?.instructions?.toList() ?: continue
                if (code.none { insn -> ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.let { it.definingClass == state && it.name == "<init>" } == true }) continue
                for (insn in code) {
                    val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                    if (insn.opcode == Opcode.INVOKE_VIRTUAL && ref.returnType == "Z" && ref.parameterTypes.size == 2 && ref.parameterTypes[1].toString() == "Z") {
                        tests += "${ref.definingClass}->${ref.name}${ref.signature()}"
                    }
                }
            }
        }
        val test = tests.singleOrNull() ?: throw PatchException("search bar Gemini test not found: $tests")
        val (testClass, testRest) = test.split("->")
        mutableClassDefBy(testClass).methods.single { "${it.name}${it.parameterTypes.joinToString("", "(", ")")}${it.returnType}" == testRest }.apply {
            if (implementation!!.registerCount < 4) throw PatchException("search bar Gemini test has no free register")
            addInstructions(0, "const/4 v0, 0x0\nreturn v0")
        }

        // 4. The Gemini button on an open conversation's toolbar: the fragment's one (boolean) -> boolean test
        //    decides it, and the action bar controller shows or hides it; both now hide it.
        val fragment = classLogging(CONVERSATION_FRAGMENT)
        val toolbarTests = fragment.methods.filter {
            it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf("Z") && !AccessFlags.STATIC.isSet(it.accessFlags)
        }
        val toolbarTest = toolbarTests.singleOrNull() ?: throw PatchException("conversation Gemini test: expected one (Z)Z method, found ${toolbarTests.size}")
        mutableClassDefBy(fragment.type).methods.single { it.name == toolbarTest.name && it.returnType == "Z" && it.parameterTypes.size == 1 }.apply {
            if (implementation!!.registerCount < 3) throw PatchException("conversation Gemini test has no free register")
            addInstructions(0, "const/4 v0, 0x0\nreturn v0")
        }
        val controller = classLogging(ACTION_BAR_CONTROLLER)
        fun Method.calls(name: String) = implementation?.instructions?.any { insn ->
            ((insn as? ReferenceInstruction)?.reference as? MethodReference)?.let { it.definingClass == MENU_ITEM && it.name == name } == true
        } == true
        val shows = controller.methods.filter {
            it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("Z") && it.calls("setVisible") && it.calls("setEnabled")
        }
        val show = shows.singleOrNull() ?: throw PatchException("action bar Gemini switch: expected one, found ${shows.size}")
        mutableClassDefBy(controller.type).methods.single { it.name == show.name && it.parameterTypes.size == 1 && it.returnType == "V" }
            .addInstructions(0, "const/4 p1, 0x0")

        // 5. Ask Gemini in a Meet call: its button is shown on (enabled) or (offered); now on neither, so it is gone.
        val meetButton = anchors.single(MEET_GEMINI_BUTTON, "Meet's Ask Gemini button").classDef
        val binds = meetButton.methods.filter { it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("Z", "Z") }
        val bind = binds.singleOrNull() ?: throw PatchException("Meet's Ask Gemini button: expected one (ZZ)V bind, found ${binds.size}")
        mutableClassDefBy(meetButton.type).methods.single { it.name == bind.name && it.parameterTypes.size == 2 && it.returnType == "V" }
            .addInstructions(0, "const/4 p1, 0x0\nconst/4 p2, 0x0")
    }
}
