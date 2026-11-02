extension {
    name = "extensions/honorweather.mpe"
}

android {
    namespace = "org.ungoogled.honorweather"

    defaultConfig {
        // Honor Weather 18.1 itself requires Android 10 (API 29).
        minSdk = 29
    }
}

// Plain Java, like the other extensions: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}

dependencies {
    // Honor Weather's own RxJava and supplier entity; see stub/.
    compileOnly(project(":extensions:honorweather:stub"))
}
