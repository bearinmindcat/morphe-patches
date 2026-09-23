extension {
    name = "extensions/extension.mpe"
}

android {
    namespace = "org.ungoogled.extension"

    defaultConfig {
        // Maps 26.36 itself requires Android 12L (API 32); matching it keeps lint
        // from rejecting the API 30+ window-insets calls the Customization screen uses.
        minSdk = 32
    }
}

// The extension is plain Java. Without this the Kotlin Gradle plugin's default
// stdlib dependency gets dexed into the extension -- ~1,100 kotlin.* classes
// merged into Maps for nothing.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}

dependencies {
    // Cronet's proxy API, which Maps bundles; see stub/.
    compileOnly(project(":extensions:extension:stub"))
}
