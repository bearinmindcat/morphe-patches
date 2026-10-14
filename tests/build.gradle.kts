import org.gradle.api.artifacts.transform.*
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.util.zip.ZipFile
import java.util.jar.JarOutputStream

plugins { java }
repositories { mavenCentral(); google() }

abstract class AarClasses : TransformAction<TransformParameters.None> {
    @get:InputArtifact @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val inputArtifact: Provider<FileSystemLocation>
    override fun transform(outputs: TransformOutputs) {
        ZipFile(inputArtifact.get().asFile).use { zip ->
            val entry = zip.getEntry("classes.jar")
            val out = outputs.file(inputArtifact.get().asFile.nameWithoutExtension + "-classes.jar")
            if (entry != null) zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            else JarOutputStream(out.outputStream()).close()
        }
    }
}
val artifactType = Attribute.of("artifactType", String::class.java)
dependencies {
    registerTransform(AarClasses::class) {
        from.attribute(artifactType, "aar")
        to.attribute(artifactType, "jar")
    }
    compileOnly("org.robolectric:android-all:15-robolectric-13954326")
    testImplementation("org.robolectric:android-all:15-robolectric-13954326")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
configurations.configureEach { if (isCanBeResolved) attributes.attribute(artifactType, "jar") }
val repo = rootDir.parentFile.absolutePath
sourceSets {
    main {
        java.srcDirs("$repo/extensions/extension/src/main/java", "$repo/extensions/extension/stub/src/main/java")
    }
    test { java.srcDir("$repo/extensions/extension/src/test/java") }
}
tasks.test {
    useJUnit()
    maxHeapSize = "2g"
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    testLogging { events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
