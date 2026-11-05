package org.ungoogled.patches.samsunggallery.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.samsunggallery.SamsungGalleryConstants.COMPATIBILITY_SAMSUNG_GALLERY
import org.w3c.dom.Element

private const val SEARCH = "org.ungoogled.samsunggallery.search"
private const val SEARCH_BY_CONTENT = "Lorg/ungoogled/samsunggallery/search/SearchByContent;"

/** The image model's network description, its weight recipe and the words (extensions/samsunggallery/native/search). */
private val ASSETS = listOf("vision.param", "vision.recipe", "labels.bin")

/**
 * The model's network, the recipe that turns the downloaded file into its weights, and the words go into the APK's
 * assets; the background job and the settings dialog into the manifest; a "Search by content" row into Gallery's
 * Search settings.
 */
private val searchResourcesPatch = resourcePatch {
    execute {
        ASSETS.forEach { name ->
            val asset = SearchResources::class.java.classLoader.getResourceAsStream("samsunggallery/search/$name")
                ?: throw PatchException("$name is missing from the patches")
            get("assets/search_by_content/$name").apply {
                parentFile.mkdirs()
                outputStream().use { out -> asset.use { it.copyTo(out) } }
            }
        }

        var packageName = ""
        document("AndroidManifest.xml").use { manifest ->
            packageName = manifest.documentElement.getAttribute("package")
            val application = manifest.getElementsByTagName("application").item(0) as Element
            application.appendChild(
                manifest.createElement("service").apply {
                    setAttribute("android:name", "$SEARCH.IndexJob")
                    setAttribute("android:permission", "android.permission.BIND_JOB_SERVICE")
                    setAttribute("android:exported", "false")
                },
            )
            application.appendChild(
                manifest.createElement("activity").apply {
                    setAttribute("android:name", "$SEARCH.SettingsActivity")
                    setAttribute("android:exported", "false")
                    setAttribute("android:excludeFromRecents", "true")
                    setAttribute("android:configChanges", "orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode|density")
                    setAttribute("android:theme", "@android:style/Theme.Translucent.NoTitleBar")
                },
            )
        }

        // Search settings (search page, More options): the row that shows progress and turns it on or off.
        document("res/xml/search_settings.xml").use { settings ->
            val screen = settings.documentElement
            if (screen.tagName != "PreferenceScreen") throw PatchException("Search settings is not a preference screen")
            val row = settings.createElement("Preference").apply {
                setAttribute("android:key", "ungoogled_search_by_content")
                setAttribute("android:title", "Search by content")
                setAttribute("android:summary", "Find photos by what's in them")
                appendChild(
                    settings.createElement("intent").apply {
                        setAttribute("android:targetPackage", packageName)
                        setAttribute("android:targetClass", "$SEARCH.SettingsActivity")
                    },
                )
            }
            // Before Gallery's trailing empty category, if it has one.
            val last = screen.lastChild
            val trailing = generateSequence(last) { it.previousSibling }.firstOrNull { it is Element }
            if (trailing != null && (trailing as Element).tagName == "PreferenceCategory" && !trailing.hasChildNodes()) {
                screen.insertBefore(row, trailing)
            } else {
                screen.appendChild(row)
            }
        }
    }
}

private object SearchResources

@Suppress("unused")
val searchByContentPatch = bytecodePatch(
    name = "Search by content",
    description = "Finds photos by what's in them, such as dogs, food or credit cards, with an on-device model.",
    default = true,
) {
    compatibleWith(COMPATIBILITY_SAMSUNG_GALLERY)
    dependsOn(enableNonOneUiPatch, nativeLibraryPatch, searchResourcesPatch)
    extendWith(SAMSUNG_GALLERY_EXTENSION)

    execute {
        // On One UI, system apps write what the photos show into Samsung's media tables, and Gallery's search and
        // Things/Scenery read them; off One UI the media copy has those tables, empty. The extension fills them from
        // an image model, after the user allowed its download once (the extension is shared: it stays off unless
        // this patch is in).
        (mutableClassDefByOrNull(SEARCH_BY_CONTENT) ?: throw PatchException("the extension has no $SEARCH_BY_CONTENT"))
            .methods.firstOrNull { it.name == "patched" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
            ?.addInstructions(0, "const/4 v0, 0x1\nreturn v0")
            ?: throw PatchException("SearchByContent has no patched()")
    }
}
