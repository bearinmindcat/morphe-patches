package org.ungoogled.patches.xiaominotes.micloud

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.xiaominotes.XiaomiNotesConstants.COMPATIBILITY_XIAOMI_NOTES
import org.ungoogled.patches.xiaominotes.misc.XIAOMI_NOTES_EXTENSION
import org.w3c.dom.Element

private val log = java.util.logging.Logger.getLogger("XiaomiNotesCloudImport")

internal const val CLOUD_IMPORT_ACTIVITY = "org.ungoogled.xiaominotes.micloud.CloudImportActivity"
private const val DAO = "Lorg/ungoogled/xiaominotes/micloud/Dao;"
private const val APP_DATABASE = "Lcom/miui/notes/storage/AppDatabase;"
private const val NOTE_ENTITY = "Lcom/miui/notes/storage/entity/NoteEntity;"
private const val FOLDER_ENTITY = "Lcom/miui/notes/storage/entity/FolderEntity;"
private const val RESTORE_HELPER = "Lcom/miui/notes/backup/modern/RestoreHelper;"

/**
 * The `documents` columns in Room's INSERT for this Notes build, which is also NoteEntity's constructor order; the
 * extension (NotesStore.COLUMNS) builds entities in this order.
 */
private const val DOCUMENT_COLUMNS = "`id`,`title`,`content`,`createdAt`,`updatedAt`,`parentId`,`noteType`,`tags`," +
    "`audioPath`,`legacyId`,`folderId`,`stickDate`,`isPinned`,`deletedAt`,`deletionTag`,`uuid`,`isPrivate`,`alertDate`," +
    "`widgetId`,`legacySyncId`,`legacyContentSha1`,`cardId`,`transcriptText`,`transcriptSentencesJson`,`source`," +
    "`derivedSource`,`doodleEntMap`,`mpfName`,`thumbnail`,`recognizedFiles`,`entMissing`,`showAiGeneratedTag`," +
    "`callDate`,`callNumber`,`importState`,`listPreview`,`firstImageUri`,`mindmapSnapshot`,`themeId`,`external_id`," +
    "`podcastId`,`lrcPath`,`transcriptPath`,`category`,`durationMs`,`podcastStatus`,`podcastPlayState`"

private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.string() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

/** Declares the import screen and adds its row under Settings > Xiaomi Cloud. */
private val cloudImportResourcesPatch = resourcePatch(
    description = "Declares the Xiaomi Cloud import screen and its Settings row.",
) {
    execute {
        val packageName = document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            val application = manifest.getElementsByTagName("application").item(0) as Element
            val activity = manifest.createElement("activity")
            activity.setAttribute("android:name", CLOUD_IMPORT_ACTIVITY)
            activity.setAttribute("android:exported", "false")
            activity.setAttribute("android:theme", "@android:style/Theme.DeviceDefault.DayNight")
            // The sign-in page keeps its state when the phone turns.
            activity.setAttribute("android:configChanges", "orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden")
            activity.setAttribute("android:windowSoftInputMode", "adjustResize")
            application.appendChild(activity)
            root.getAttribute("package")
        }
        if (packageName.isNullOrEmpty()) throw PatchException("manifest has no package")

        document("res/xml/preferences_settings.xml").use { settings ->
            val preferences = settings.getElementsByTagName("*")
            var category: Element? = null
            for (i in 0 until preferences.length) {
                val e = preferences.item(i) as Element
                if (e.getAttribute("android:key") == "pref_category_cloud") category = e
            }
            category ?: throw PatchException("Settings has no Xiaomi Cloud section (pref_category_cloud)")
            val row = settings.createElement("Preference")
            row.setAttribute("android:key", "pref_ungoogled_micloud_import")
            row.setAttribute("android:title", "Import from Xiaomi Cloud")
            row.setAttribute("android:summary", "Copy your notes from i.mi.com to this phone")
            row.setAttribute("android:persistent", "false")
            // androidx.preference opens a row's <intent> when it is tapped.
            val intent = settings.createElement("intent")
            intent.setAttribute("android:targetPackage", packageName)
            intent.setAttribute("android:targetClass", CLOUD_IMPORT_ACTIVITY)
            row.appendChild(intent)
            category.appendChild(row)
        }
    }
}

/** Replaces the extension's Dao stubs with calls into this Notes build's database and converter code. */
private val cloudImportBridgePatch = bytecodePatch(
    description = "Connects the Xiaomi Cloud import to Notes' database and note converter.",
) {
    extendWith(XIAOMI_NOTES_EXTENSION)

    execute {
        checkDocumentColumns()
        val noteInsert = noteInsertSmali()
        val folderInsert = folderInsertSmali()
        val converter = converterMethod()

        val dao = mutableClassDefBy(DAO)
        fun replace(name: String, parameters: List<String>, returnType: String, registers: Int, smali: String) {
            val stub = dao.methods.singleOrNull { it.name == name } ?: throw PatchException("extension Dao.$name not found")
            dao.methods.remove(stub)
            dao.methods.add(
                ImmutableMethod(
                    DAO, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
                    AccessFlags.STATIC.value, null, null, MutableMethodImplementation(registers),
                ).toMutable().apply { addInstructions(0, smali) },
            )
        }
        replace("putNote", listOf("Ljava/lang/Object;", "Ljava/lang/Object;"), "J", 8, noteInsert)
        replace("addFolder", listOf("Ljava/lang/Object;", "Ljava/lang/Object;"), "J", 4, folderInsert)
        replace(
            "toMarkdown", listOf("Ljava/lang/String;"), "Ljava/lang/String;", 2,
            """
                invoke-static { p0 }, ${converter.definingClass}->${converter.name}(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                return-object v0
            """,
        )
        log.info("Xiaomi Cloud import bridge: converter ${converter.definingClass}->${converter.name}")
    }
}

/** NotesStore builds NoteEntity in the column order of Room's INSERT; stop if this build's order differs. */
private fun BytecodePatchContext.checkDocumentColumns() {
    var found = false
    classDefForEach { c ->
        if (found || c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) {
            if (m.implementation?.instructions?.any { it.string()?.startsWith("INSERT OR REPLACE INTO `documents` (") == true } == true) {
                val sql = m.implementation!!.instructions.mapNotNull { it.string() }
                    .first { it.startsWith("INSERT OR REPLACE INTO `documents` (") }
                if (!sql.contains("($DOCUMENT_COLUMNS)")) throw PatchException("documents columns changed: $sql")
                found = true
                return@classDefForEach
            }
        }
    }
    if (!found) throw PatchException("Room's INSERT for documents not found")
    val columns = DOCUMENT_COLUMNS.split(',').size
    if (classDefBy(NOTE_ENTITY).methods.none { it.name == "<init>" && it.parameterTypes.size == columns }) {
        throw PatchException("NoteEntity has no $columns-parameter constructor")
    }
}

/**
 * Notes' note DAO (the one AgenticNoteProvider.insertExternalNote and the backup restore use) runs each write as
 * `performBlocking(db, false, true, new Lambda(dao, entity, N))`: Room's statement inside a write transaction, which
 * tells the note list to reload. Dao.putNote does the same with the lambda variant whose statement is `INSERT OR REPLACE
 * INTO documents` for a new note (id 0) and `UPDATE OR ABORT documents ... WHERE id = ?` for an existing one (an update
 * keeps the row, unlike REPLACE, so nothing hanging off the note is dropped). The variants are read from the lambda's
 * switch and the DAO's adapters, not assumed.
 */
private fun BytecodePatchContext.noteInsertSmali(): String {
    // The DAO's synchronous insert, `long x(NoteEntity)`: its body names the lambda, Room's call and the database field.
    val daoTypes = classDefBy(APP_DATABASE).methods
        .filter { it.parameterTypes.isEmpty() && AccessFlags.ABSTRACT.isSet(it.accessFlags) }.map { it.returnType }.toSet()
    val daos = mutableListOf<String>()
    classDefForEach { c -> if (c.interfaces.any { it in daoTypes } && c.methods.any { it.isNoteInsert() }) daos += c.type }
    val dao = daos.singleOrNull() ?: throw PatchException("note DAO with a synchronous NoteEntity insert not found: $daos")
    val daoClass = classDefBy(dao)
    val insert = daoClass.methods.single { it.isNoteInsert() }.implementation!!.instructions.toList()
    val init = insert.mapNotNull { it.methodRef() }.firstOrNull { ref ->
        ref.name == "<init>" && ref.parameterTypes.map { it.toString() } == listOf(dao, NOTE_ENTITY, "I")
    } ?: throw PatchException("DAO insert lambda not found")
    val lambda = init.definingClass
    val perform = insert.mapNotNull { it.methodRef() }.firstOrNull { ref ->
        ref.returnType == "Ljava/lang/Object;" && ref.parameterTypes.size == 4 &&
            ref.parameterTypes[1].toString() == "Z" && ref.parameterTypes[2].toString() == "Z" &&
            ref.parameterTypes[3].toString() in classDefBy(lambda).interfaces
    } ?: throw PatchException("Room performBlocking call not found in the DAO insert")
    val dbField = insert.firstNotNullOfOrNull { insn ->
        ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf {
            insn.opcode == Opcode.IGET_OBJECT && it.definingClass == dao && it.type == perform.parameterTypes[0].toString()
        }
    } ?: throw PatchException("DAO database field not found")

    // Which lambda variant runs which statement: lambda switch -> DAO adapter field -> adapter index -> SQL.
    val adapters = fieldAdapters(daoClass)
    val variants = lambdaFields(lambda, dao).mapValues { (_, field) ->
        adapters[field]?.let { (type, index) -> adapterSql(type)[index] }
    }
    fun variant(sqlPrefix: String) = variants.entries.firstOrNull { it.value?.startsWith(sqlPrefix) == true }?.key
        ?: throw PatchException("no DAO lambda variant runs \"$sqlPrefix\" ($variants)")
    val insertVariant = variant("INSERT OR REPLACE INTO `documents`")
    val updateVariant = variant("UPDATE OR ABORT `documents`")
    if (classDefBy(NOTE_ENTITY).methods.none { it.name == "getId" && it.parameterTypes.isEmpty() && it.returnType == "J" }) {
        throw PatchException("NoteEntity.getId() not found")
    }
    val daoGetter = appDatabaseGetter(dao)
    log.info("Note writes: $dao via $lambda (insert $insertVariant, update $updateVariant), ${perform.definingClass}->${perform.name}")
    fun literal(v: Int) = if (v in -8..7) "const/4 v3, $v" else "const/16 v3, $v"
    return """
        check-cast p0, $APP_DATABASE
        invoke-virtual { p0 }, $APP_DATABASE->${daoGetter.name}()${daoGetter.returnType}
        move-result-object v0
        check-cast v0, $dao
        iget-object v1, v0, $dbField
        check-cast p1, $NOTE_ENTITY
        invoke-virtual { p1 }, $NOTE_ENTITY->getId()J
        move-result-wide v2
        const-wide/16 v4, 0x0
        cmp-long v2, v2, v4
        if-nez v2, :update
        new-instance v2, $lambda
        ${literal(insertVariant)}
        invoke-direct { v2, v0, p1, v3 }, $init
        const/4 v3, 0x0
        const/4 v4, 0x1
        invoke-static { v1, v3, v4, v2 }, $perform
        move-result-object v0
        check-cast v0, Ljava/lang/Number;
        invoke-virtual { v0 }, Ljava/lang/Number;->longValue()J
        move-result-wide v0
        return-wide v0
        :update
        new-instance v2, $lambda
        ${literal(updateVariant)}
        invoke-direct { v2, v0, p1, v3 }, $init
        const/4 v3, 0x0
        const/4 v4, 0x1
        invoke-static { v1, v3, v4, v2 }, $perform
        invoke-virtual { p1 }, $NOTE_ENTITY->getId()J
        move-result-wide v0
        return-wide v0
    """
}

private fun Method.isNoteInsert() = returnType == "J" && parameterTypes.map { it.toString() } == listOf(NOTE_ENTITY) &&
    !AccessFlags.STATIC.isSet(accessFlags) && implementation != null

/** Instruction index of each case of the first switch in [instructions]: key -> index. */
private fun switchTargets(instructions: List<Instruction>): Map<Int, Int> {
    val address = IntArray(instructions.size)
    var a = 0
    instructions.forEachIndexed { i, insn ->
        address[i] = a
        a += insn.codeUnits
    }
    val at = instructions.indexOfFirst { it.opcode == Opcode.PACKED_SWITCH || it.opcode == Opcode.SPARSE_SWITCH }
    if (at < 0) return emptyMap()
    val payloadAddress = address[at] + (instructions[at] as OffsetInstruction).codeOffset
    val payload = instructions[address.indexOfFirst { it == payloadAddress }] as SwitchPayload
    return payload.switchElements.associate { e -> e.key to address.indexOfFirst { it == address[at] + e.offset } }
}

/** Adapter fields a DAO's constructor sets: field name -> (adapter class, index passed to its constructor). */
private fun fieldAdapters(dao: com.android.tools.smali.dexlib2.iface.ClassDef): Map<String, Pair<String, Int>> {
    val out = mutableMapOf<String, Pair<String, Int>>()
    for (ctor in dao.methods.filter { it.name == "<init>" }) {
        val insns = ctor.implementation?.instructions?.toList() ?: continue
        insns.forEachIndexed { i, insn ->
            if (insn.opcode != Opcode.IPUT_OBJECT) return@forEachIndexed
            val field = (insn as ReferenceInstruction).reference as FieldReference
            val init = (i - 1 downTo 0).firstOrNull { insns[it].methodRef()?.name == "<init>" } ?: return@forEachIndexed
            val ref = insns[init].methodRef()!!
            if (ref.definingClass != field.type || ref.parameterTypes.map { it.toString() } != listOf("I")) return@forEachIndexed
            val register = (insns[init] as FiveRegisterInstruction).registerD
            val literal = (init - 1 downTo 0).map { insns[it] }.firstOrNull { it is OneRegisterInstruction && it.registerA == register }
                as? NarrowLiteralInstruction ?: return@forEachIndexed
            out[field.name] = field.type to literal.narrowLiteral
        }
    }
    return out
}

/** Which DAO field each case of the lambda's switch uses: variant -> field name. */
private fun BytecodePatchContext.lambdaFields(lambda: String, dao: String): Map<Int, String> {
    val invoke = classDefBy(lambda).methods.firstOrNull { it.name == "invoke" && it.parameterTypes.size == 1 }
        ?: throw PatchException("lambda $lambda has no invoke")
    val insns = invoke.implementation!!.instructions.toList()
    return switchTargets(insns).mapNotNull { (key, target) ->
        (target until insns.size).map { insns[it] }.firstNotNullOfOrNull { insn ->
            ((insn as? ReferenceInstruction)?.reference as? FieldReference)?.takeIf {
                insn.opcode == Opcode.IGET_OBJECT && it.definingClass == dao
            }?.name
        }?.let { key to it }
    }.toMap()
}

/** A Room adapter class's statements: constructor index -> SQL (its statement getter switches on the index). */
private fun BytecodePatchContext.adapterSql(type: String): Map<Int, String> {
    val out = mutableMapOf<Int, String>()
    for (m in classDefBy(type).methods) {
        val insns = m.implementation?.instructions?.toList() ?: continue
        if (insns.none { it.string()?.contains("`documents`") == true }) continue
        for ((key, target) in switchTargets(insns)) {
            insns.drop(target).firstNotNullOfOrNull { it.string() }?.let { out.putIfAbsent(key, it) }
        }
    }
    return out
}

/** The folder insert RestoreHelper.ensureSystemFolder uses: the folder DAO's `long insert(FolderEntity)`. */
private fun BytecodePatchContext.folderInsertSmali(): String {
    val ensure = classDefBy(RESTORE_HELPER).methods.firstOrNull { it.name == "ensureSystemFolder" }
        ?: throw PatchException("RestoreHelper.ensureSystemFolder not found")
    val call = ensure.implementation!!.instructions.mapNotNull { it.methodRef() }.firstOrNull { ref ->
        ref.returnType == "J" && ref.parameterTypes.map { it.toString() } == listOf(FOLDER_ENTITY)
    } ?: throw PatchException("folder insert not found in ensureSystemFolder")
    val getter = appDatabaseGetter(call.definingClass)
    log.info("Folder insert: ${call.definingClass}->${call.name}, AppDatabase.${getter.name}")
    return """
        check-cast p0, $APP_DATABASE
        invoke-virtual { p0 }, $APP_DATABASE->${getter.name}()${getter.returnType}
        move-result-object v0
        check-cast v0, ${call.definingClass}
        check-cast p1, $FOLDER_ENTITY
        invoke-virtual { v0, p1 }, $call
        move-result-wide v0
        return-wide v0
    """
}

/** AppDatabase's abstract DAO getter whose type [daoImpl] implements. */
private fun BytecodePatchContext.appDatabaseGetter(daoImpl: String): Method {
    val interfaces = classDefBy(daoImpl).interfaces.toSet()
    return classDefBy(APP_DATABASE).methods.singleOrNull { m ->
        m.parameterTypes.isEmpty() && AccessFlags.ABSTRACT.isSet(m.accessFlags) && m.returnType in interfaces
    } ?: throw PatchException("AppDatabase getter for $daoImpl ($interfaces) not found")
}

/** Notes' converter from Xiaomi Cloud / MIUI note content to Markdown ("SchemaToMd"), static String -> String. */
private fun BytecodePatchContext.converterMethod(): Method {
    var found: Method? = null
    classDefForEach { c ->
        if (found != null || c.type.startsWith("Lorg/ungoogled/")) return@classDefForEach
        for (m in c.methods) {
            if (!AccessFlags.STATIC.isSet(m.accessFlags) || m.returnType != "Ljava/lang/String;" ||
                m.parameterTypes.map { it.toString() } != listOf("Ljava/lang/String;")
            ) continue
            val strings = m.implementation?.instructions?.mapNotNull { it.string() } ?: continue
            if ("SchemaToMd" in strings && "convert failed: " in strings) {
                found = m
                return@classDefForEach
            }
        }
    }
    return found ?: throw PatchException("Notes' note converter (SchemaToMd) not found")
}

private const val NOTE_LIST = "Lcom/miui/notes/notesui/feature/note/presentation/list/AINoteListActivity;"

/**
 * The note list's onResume tells CloudSync that Notes came to the front: if the user turned on the experimental sync,
 * it syncs in the background (at most every few minutes); otherwise it returns at once.
 */
private val cloudSyncOnOpenPatch = bytecodePatch(
    description = "Lets the experimental Xiaomi Cloud sync run when Notes opens.",
) {
    extendWith(XIAOMI_NOTES_EXTENSION)

    execute {
        val onResume = mutableClassDefByOrNull(NOTE_LIST)?.methods?.firstOrNull {
            it.name == "onResume" && it.parameterTypes.isEmpty() && it.returnType == "V"
        } ?: throw PatchException("AINoteListActivity.onResume not found")
        onResume.addInstruction(
            0,
            "invoke-static { p0 }, Lorg/ungoogled/xiaominotes/micloud/CloudSync;->onAppOpen(Landroid/app/Activity;)V",
        )
    }
}

/**
 * Settings > Xiaomi Cloud > Import from Xiaomi Cloud (extension package org.ungoogled.xiaominotes.micloud): the user
 * signs in on Xiaomi's own notes website (i.mi.com) in a WebView; the notes are then read with that session the way the
 * website reads them, converted with Notes' own converter and written through Notes' own DAO, with their dates,
 * folders, checklists, pictures and recordings. Re-runnable without duplicates; encrypted and hidden notes are counted,
 * not imported. The same screen has an experimental two-way sync, off by default.
 */
@Suppress("unused")
val importFromXiaomiCloudPatch = bytecodePatch(
    name = "Import from Xiaomi Cloud",
    description = "Copies your notes from Xiaomi Cloud (i.mi.com) into the app.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_XIAOMI_NOTES)
    dependsOn(cloudImportResourcesPatch, cloudImportBridgePatch, cloudSyncOnOpenPatch)
    extendWith(XIAOMI_NOTES_EXTENSION)
}
