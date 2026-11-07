group = "org.ungoogled"

patches {
    about {
        name = "bearinmind patches"
        description = "Ungoogled Maps and microG Maps: de-Googling, privacy and UI patches for Google Maps. Waze: true black, no ads, speed camera and alert settings. AllTrails: better tracking and filters, 2D/3D compass, themes. Gmail: no ads, no Gemini, no upsells, telemetry off, tracking pixels blocked, link trackers stripped, Smart Compose off, black theme, and microG Gmail that signs in through microG-RE. Samsung Keyboard: telemetry off, no GIF tracking, always incognito. Samsung Internet: no 99-tab limit, no address bar blur, theme color on the bottom bar, hide scroll bar option, no update badge, telemetry off. Samsung apps on non-Samsung phones: Gallery, Notes, My Files, Music, Health Monitor, Video Player, Photo Editor, Calendar, Reminder, Weather, Modes and Routines, Messages, Contacts, Phone, Email, Keyboard, Clock, Calculator, Voice Recorder, Screen Recorder, Capture (screen recorder and screenshot editor), Good Lock, Home Up (with One UI Home), Wonderland, One Hand Operation + (through an accessibility service), and Sound Assistant (individual app volumes). Samsung Gallery, Notes and Sound Assistant: telemetry off. Samsung Notes: Samsung account sign-in and Samsung Cloud sync (experimental). Samsung Notes: import and export of notes (.sdocx). Samsung Notes and Gallery: Google services removed. Samsung Gallery: Extract text with an on-device text recognizer. Samsung Health: health data sharing on non-Samsung phones (experimental). Xiaomi apps on non-Xiaomi phones: Calendar, Compass, Gallery, Gallery Editor, Notes, Recorder, Scanner, Screen Recorder, Weather. Nothing apps on non-Nothing phones: Gallery, Recorder, Screenshot editor. Honor apps on non-Honor phones: Calculator, Video Player, Compass, Clock, Recorder, Calendar, Files, Notes, Weather, Screen recording. RedMagic apps on non-RedMagic phones: Game Space. LG apps on non-LG phones: QuickMemo+, Gallery. ColorOS apps on non-ColorOS phones: Calculator, Weather, Photos, My Files, Notes, Screen Recording, Compass, Clock, Recorder, Documents, Contacts and dial pad. Sony apps on non-Sony phones: Album, Music, Xperia Keyboard. Launchers on any phone, no root: Samsung One UI Home, Honor Home, OnePlus System Launcher, OnePlus Launcher (OxygenOS 11), Nothing Launcher, Xperia Home, ASUS Launcher, Moto App Launcher, HyperOS launcher. LG Health: telemetry off. OnePlus Camera on OnePlus phones (root mount install): telemetry off, no background running. PdfCraft: custom icon and app name."
        source = "git@github.com:bearinmindcat/morphe-patches.git"
        author = "bearinmindcat"
        contact = "https://github.com/bearinmindcat"
        website = "https://github.com/bearinmindcat/morphe-patches"
        license = "GPLv3"
    }
}

// Separate configuration so gson is available at runtime for the
// generatePatchesList task but never bundled into the APK.
val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        dependsOn(build)

        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    // Used by gradle-semantic-release-plugin.
    publish {
        dependsOn("generatePatchesList")
    }
}
