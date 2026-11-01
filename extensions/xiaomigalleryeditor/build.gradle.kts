extension {
    name = "extensions/xiaomigalleryeditor.mpe"
}

android {
    namespace = "org.ungoogled.xiaomigalleryeditor"

    defaultConfig {
        // Xiaomi Gallery Editor 2.10 itself requires Android 13 (API 33).
        minSdk = 33
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
