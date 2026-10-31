extension {
    name = "extensions/samsungcontacts.mpe"
}

android {
    namespace = "org.ungoogled.samsungcontacts"

    defaultConfig {
        // Samsung Contacts 16.3 itself requires Android 15 (API 35).
        minSdk = 35
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
