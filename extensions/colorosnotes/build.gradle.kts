extension {
    name = "extensions/colorosnotes.mpe"
}

android {
    namespace = "org.ungoogled.colorosnotes"

    defaultConfig {
        // OnePlus Notes 16.6 itself requires Android 12 (API 31).
        minSdk = 31
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
