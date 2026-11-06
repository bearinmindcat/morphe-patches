extension {
    name = "extensions/gmail.mpe"
}

android {
    namespace = "org.ungoogled.gmail"

    defaultConfig {
        // Gmail 2026.11 itself requires Android 12L (API 32).
        minSdk = 32
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}

dependencies {
    // Guava's ListenableFuture, which Gmail bundles; see stub/.
    compileOnly(project(":extensions:gmail:stub"))
}
