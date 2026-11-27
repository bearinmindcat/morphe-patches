import java.util.Properties

// Compile-only stand-ins for the androidx.preference classes Pixel Launcher ships (its Home settings screen is
// built from them). They only let the extension compile and are never packaged into it; the patch checks that the
// app's own classes have every member used here.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

// The stand-ins take Android types (Context, AttributeSet): compile against the platform the extensions use (36).
val sdkDir: String? = rootProject.file("local.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }.getProperty("sdk.dir")
} ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")

dependencies {
    compileOnly(files("$sdkDir/platforms/android-36/android.jar"))
}
