extension {
    name = "extensions/samsunghomeup.mpe"
}

android {
    namespace = "org.ungoogled.samsunghomeup"

    defaultConfig {
        // Home Up 18.0 asks for Android 16; the port runs it on Android 15 (API 35) like One UI Home.
        minSdk = 35
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
