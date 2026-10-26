package org.ungoogled.patches.maps.microg

import app.morphe.patcher.patch.AvailabilityResolver
import app.morphe.patcher.patch.InstallerType
import app.morphe.patcher.patch.PatchAvailability
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import org.ungoogled.patches.shared.Constants.COMPATIBILITY_MAPS
import org.w3c.dom.Element

private const val STOCK_PACKAGE = "com.google.android.apps.maps"
private const val FAKE_PACKAGE_SIGNATURE = "android.permission.FAKE_PACKAGE_SIGNATURE"

/**
 * Stock Maps' original signing certificate, DER in hex (the legacy signer, SHA-1 38918a45...). Android
 * reports a key-rotated app's original signer to GET_SIGNATURES, which is what original microG reads, so
 * this is what stock Maps shows it -- and the same certificate microG declares as its own fake-signature.
 */
private const val STOCK_CERT =
    "308204433082032ba003020102020900c2e08746644a308d300d06092a864886f70d01010405003074310b30090603550406" +
    "13025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731" +
    "143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307" +
    "416e64726f6964301e170d3038303832313233313333345a170d3336303130373233313333345a3074310b30090603550406" +
    "13025553311330110603550408130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731" +
    "143012060355040a130b476f6f676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307" +
    "416e64726f696430820120300d06092a864886f70d01010105000382010d00308201080282010100ab562e00d83ba208ae0a" +
    "966f124e29da11f2ab56d08f58e2cca91303e9b754d372f640a71b1dcb130967624e4656a7776a92193db2e5bfb724a91e77" +
    "188b0e6a47a43b33d9609b77183145ccdf7b2e586674c9e1565b1f4c6a5955bff251a63dabf9c55c27222252e875e4f8154a" +
    "645f897168c0b1bfc612eabf785769bb34aa7984dc7e2ea2764cae8307d8c17154d7ee5f64a51a44a602c249054157dc02cd" +
    "5f5c0e55fbef8519fbe327f0b1511692c5a06f19d18385f5c4dbc2d6b93f68cc2979c70e18ab93866b3bd5db8999552a0e3b" +
    "4c99df58fb918bedc182ba35e003c1b4b10dd244a8ee24fffd333872ab5221985edab0fc0d0b145b6aa192858e79020103a3" +
    "81d93081d6301d0603551d0e04160414c77d8cc2211756259a7fd382df6be398e4d786a53081a60603551d2304819e30819b" +
    "8014c77d8cc2211756259a7fd382df6be398e4d786a5a178a4763074310b3009060355040613025553311330110603550408" +
    "130a43616c69666f726e6961311630140603550407130d4d6f756e7461696e205669657731143012060355040a130b476f6f" +
    "676c6520496e632e3110300e060355040b1307416e64726f69643110300e06035504031307416e64726f6964820900c2e087" +
    "46644a308d300c0603551d13040530030101ff300d06092a864886f70d010104050003820101006dd252ceef85302c360aaa" +
    "ce939bcff2cca904bb5d7a1661f8ae46b2994204d0ff4a68c7ed1a531ec4595a623ce60763b167297a7ae35712c407f208f0" +
    "cb109429124d7b106219c084ca3eb3f9ad5fb871ef92269a8be28bf16d44c8d9a08e6cb2f005bb3fe2cb96447e868e731076" +
    "ad45b33f6009ea19c161e62641aa99271dfd5228c5c587875ddb7f452758d661f6cc0cccb7352e424cc4365c523532f73251" +
    "37593c4ae341f4db41edda0d0b1071a7c440f0fe9ea01cb627ca674369d084bd2fd911ff06cdbf2cfa10dc0f893ae3576291" +
    "9048c7efc64c7144178342f70581c9de573af55b390dd7fdb9418631895d5f759f30112687ff621410c069308a"

/**
 * Original microG (in Play services' place, on a ROM with signature spoofing) only lists accounts for, and
 * fetches tokens as, an app it sees as Google's: stock Maps' package name and certificate (issue #75). The
 * ROM shows apps that hold FAKE_PACKAGE_SIGNATURE the certificate in their fake-signature meta-data.
 */
private val originalMicrogManifestPatch = resourcePatch(
    description = "Declares stock Maps' certificate for signature spoofing.",
) {
    execute {
        // Runs first as the patch's dependency, before any resource patch it changes.
        MicrogSelection.select(this, MicrogSelection.Flavor.ORIGINAL)

        document("AndroidManifest.xml").use { manifest ->
            fun all(tag: String) = manifest.getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
            val root = manifest.documentElement
            if (root.getAttribute("package") != STOCK_PACKAGE) throw PatchException("expected $STOCK_PACKAGE, found ${root.getAttribute("package")}")
            val application = all("application").single()
            if (all("uses-permission").none { it.getAttribute("android:name") == FAKE_PACKAGE_SIGNATURE }) {
                root.insertBefore(manifest.createElement("uses-permission").apply {
                    setAttribute("android:name", FAKE_PACKAGE_SIGNATURE)
                }, application)
            }
            application.appendChild(manifest.createElement("meta-data").apply {
                setAttribute("android:name", "fake-signature")
                setAttribute("android:value", STOCK_CERT)
            })
        }
    }
}

@Suppress("unused")
val originalMicrogSupportPatch = bytecodePatch(
    name = "Add original microG support",
    description = "Signs in through original microG on ROMs with signature spoofing. Keeps Maps' own package " +
        "name; allow signature spoofing for it.",
    default = false,
) {
    compatibleWith(COMPATIBILITY_MAPS)
    category("microG")
    // A mount install keeps stock Maps and its real signature, which original microG already accepts.
    availability(AvailabilityResolver { installer, _ ->
        if (installer == InstallerType.MOUNT) PatchAvailability.UNAVAILABLE else PatchAvailability.DISABLED
    })
    dependsOn(originalMicrogManifestPatch)

    execute {
        MicrogSelection.select(this, MicrogSelection.Flavor.ORIGINAL)
    }
}
