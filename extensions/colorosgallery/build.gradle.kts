extension {
    name = "extensions/colorosgallery.mpe"
}

android {
    namespace = "org.ungoogled.colorosgallery"

    defaultConfig {
        // OnePlus Photos 15.33.40 runs on Android 11 (API 30); 16.45.2 needs 13. Not higher: the dexer would drop the
        // extension's checks for newer Android.
        minSdk = 30
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
