package org.ungoogled.patches.samsunggallery.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.ungoogled.patches.samsunggallery.SamsungGalleryConstants.COMPATIBILITY_SAMSUNG_GALLERY
import org.w3c.dom.Element

/** Gallery's bottom bar, its tab listener and the Menu tab's entries; unobfuscated. */
private const val SELECT_LISTENER = "Lcom/samsung/android/gallery/app/ui/container/menu/BottomTabSelectListener;"
private const val TAB_CONSUMER = "Lcom/samsung/android/gallery/app/ui/container/abstraction/ITabConsumer;"
private const val MENU_ITEM = "Lcom/samsung/android/gallery/app/ui/container/menu/BottomMenuItem;"
private const val MENU_HOLDER = "Lcom/samsung/android/gallery/app/ui/container/menu/BottomMenuFactory\$MenuHolder;"
private const val BLACKBOARD = "Lcom/samsung/android/gallery/support/blackboard/Blackboard;"
private const val VIRTUAL_BUCKETS = "Lcom/samsung/android/gallery/support/utils/BucketUtils\$VirtualBucketHolder;"
private const val MEDIA_ITEM = "Lcom/samsung/android/gallery/module/data/MediaItem;"
private const val OPEN = "openShortcutTab"

/**
 * A tab: its id (an id Gallery already has, used here only as the tab's tag: ids added by a patch get their numbers
 * after the code is patched), the Menu entry it opens (MenuHolder field), its icon and title, and its virtual album.
 */
private class Tab(val key: String, val id: String, val holder: String, val icon: String, val title: String, val bucket: String?)

private val TABS = listOf(
    Tab("videos", "video", "VIDEOS", "gallery_ic_menu_video", "smart_album_videos", null),
    Tab("favorites", "action_favorite_view", "FAVORITES", "gallery_ic_menu_favorite", "smart_album_favorites", "favorite"),
    Tab("recent", "recently_history", "RECENT", "gallery_ic_menu_recent", "recently_added", "recent"),
)

@Suppress("unused")
val navigationBarTabsPatch = bytecodePatch(
    name = "Navigation bar tabs",
    description = "Adds Videos, Favorites and Recent to the navigation bar.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_GALLERY)

    val chosen = TABS.associateWith { tab ->
        booleanOption(
            key = tab.key,
            default = true,
            title = tab.key.replaceFirstChar(Char::uppercase),
            description = "Shows a ${tab.key.replaceFirstChar(Char::uppercase)} tab.",
            required = true,
        )
    }

    // The chosen tabs go in front of Menu in the bottom bar's menu.
    dependsOn(
        resourcePatch {
            execute {
                document("res/menu/menu_bottom_tab.xml").use { xml ->
                    val items = xml.getElementsByTagName("item")
                    val menu = (0 until items.length).map { items.item(it) as Element }
                        .firstOrNull { it.getAttribute("android:id") == "@id/action_menu_list" }
                        ?: throw PatchException("Gallery's bottom bar has no Menu tab")
                    for (tab in TABS.filter { chosen.getValue(it).value == true }) {
                        menu.parentNode.insertBefore(
                            xml.createElement("item").apply {
                                setAttribute("android:icon", "@drawable/${tab.icon}")
                                setAttribute("android:enabled", "true")
                                setAttribute("android:id", "@id/${tab.id}")
                                setAttribute("android:title", "@string/${tab.title}")
                            },
                            menu,
                        )
                    }
                }
            }
        },
    )

    execute {
        val listener = mutableClassDefBy(SELECT_LISTENER)
        val onTabSelected = listener.methods.firstOrNull { it.name == "onTabSelected" && it.parameterTypes.size == 1 }
            ?: throw PatchException("$SELECT_LISTENER has no onTabSelected(Tab)")
        val tabType = onTabSelected.parameterTypes.single().toString()
        // The tab's id is its tag, read by the Menu tab check (isMenuTab): tab.<field>.intValue() == action_menu_list.
        val tag = listener.methods.firstOrNull { it.name == "isMenuTab" }?.implementation?.instructions
            ?.firstOrNull { it.opcode == Opcode.IGET_OBJECT }?.let { (it as ReferenceInstruction).reference as FieldReference }
            ?.takeIf { it.definingClass == tabType && it.type == "Ljava/lang/Integer;" }
            ?: throw PatchException("$SELECT_LISTENER.isMenuTab no longer reads the tab's Integer id")

        // A shortcut tab opens its album the way the Menu tab's entry does (its locationKey, and for Favorites and Recent
        // the album published first), and the bar goes back to the tab that was showing, as after Menu.
        val ids = TABS.associateWith { resourceId(ResourceType.ID, it.id).toInt() }
        val open = ImmutableMethod(
            SELECT_LISTENER, OPEN, listOf(ImmutableMethodParameter(SELECT_LISTENER, null, null), ImmutableMethodParameter(tabType, null, null)),
            "Z", AccessFlags.PRIVATE.value or AccessFlags.STATIC.value, null, null, MutableMethodImplementation(6),
        ).toMutable()
        open.addInstructionsWithLabels(
            0,
            buildString {
                appendLine("iget-object v0, p1, $tag")
                appendLine("if-eqz v0, :no")
                appendLine("invoke-virtual { v0 }, Ljava/lang/Integer;->intValue()I")
                appendLine("move-result v0")
                for (tab in TABS) {
                    appendLine("const v1, ${ids.getValue(tab)}")
                    appendLine("if-eq v0, v1, :${tab.key}")
                }
                appendLine("goto :no")
                for (tab in TABS) {
                    appendLine(":${tab.key}")
                    appendLine("sget-object v2, $MENU_HOLDER->${tab.holder}:$MENU_ITEM")
                    appendLine("const/4 v3, 0x0")
                    if (tab.bucket != null) {
                        appendLine("sget v0, $VIRTUAL_BUCKETS->${tab.bucket}:I")
                        appendLine("invoke-static { v0 }, Lcom/samsung/android/gallery/module/album/AlbumTitleHelper;->getAlbumTitle(I)Ljava/lang/String;")
                        appendLine("move-result-object v1")
                        appendLine("invoke-static { v0, v1 }, Lcom/samsung/android/gallery/module/data/MediaItemBuilder;->createVirtualAlbum(ILjava/lang/String;)$MEDIA_ITEM")
                        appendLine("move-result-object v3")
                    }
                    appendLine("goto :open")
                }
                // While tabs can't be switched (the listener then puts the bar back) nothing opens either.
                appendLine(":open")
                appendLine("iget-object v0, p0, $SELECT_LISTENER->mTabConsumer:$TAB_CONSUMER")
                appendLine("invoke-interface { v0 }, $TAB_CONSUMER->checkTabSelectable()Z")
                appendLine("move-result v1")
                appendLine("if-eqz v1, :no")
                appendLine("const/4 v1, 0x1")
                appendLine("invoke-interface { v0, v1 }, $TAB_CONSUMER->cancel(Z)V")
                appendLine("invoke-interface { v0 }, $TAB_CONSUMER->getBlackboard()$BLACKBOARD")
                appendLine("move-result-object v0")
                appendLine("if-eqz v0, :done")
                appendLine("if-eqz v3, :move")
                appendLine("const-string v1, \"data://albums/current\"")
                appendLine("invoke-virtual { v0, v1, v3 }, $BLACKBOARD->publish(Ljava/lang/String;Ljava/lang/Object;)V")
                appendLine(":move")
                appendLine("iget-object v2, v2, $MENU_ITEM->locationKey:Ljava/lang/String;")
                appendLine("const-string v1, \"command://MoveURL\"")
                appendLine("invoke-virtual { v0, v1, v2 }, $BLACKBOARD->post(Ljava/lang/String;Ljava/lang/Object;)V")
                appendLine(":done")
                appendLine("const/4 v0, 0x1")
                appendLine("return v0")
                appendLine(":no")
                appendLine("const/4 v0, 0x0")
                appendLine("return v0")
            },
        )
        listener.methods.add(open)

        // Ahead of everything else the listener does with a selected tab.
        onTabSelected.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0, p1 }, $SELECT_LISTENER->$OPEN($SELECT_LISTENER$tabType)Z
                move-result v0
                if-eqz v0, :select
                return-void
            """,
            ExternalLabel("select", onTabSelected.implementation!!.instructions.first()),
        )
    }
}
