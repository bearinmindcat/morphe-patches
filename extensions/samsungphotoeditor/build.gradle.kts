extension {
    name = "extensions/samsungphotoeditor.mpe"
}

android {
    namespace = "org.ungoogled.samsungphotoeditor"

    defaultConfig {
        // Photo Editor 3.4.14.63, the oldest version patched, requires Android 13 (API 33).
        minSdk = 33
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
