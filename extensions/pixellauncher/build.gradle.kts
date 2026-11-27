extension {
    name = "extensions/pixellauncher.mpe"
}

android {
    namespace = "org.ungoogled.pixellauncher"

    defaultConfig {
        // The Pixel Launcher builds this patches (Android 16 and 17) need Android 16 (API 36).
        minSdk = 36
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}

dependencies {
    // The androidx.preference classes Pixel Launcher ships (its Home settings screen); see stub/.
    compileOnly(project(":extensions:pixellauncher:stub"))
}
