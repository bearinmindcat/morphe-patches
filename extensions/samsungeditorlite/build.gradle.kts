extension {
    name = "extensions/samsungeditorlite.mpe"
}

android {
    namespace = "org.ungoogled.samsungeditorlite"

    defaultConfig {
        // Editor Lite 4.0.85 asks for a newer Android; the patch lets it run on Android 13 (API 33).
        minSdk = 33
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
