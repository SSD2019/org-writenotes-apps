import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
}

val nativeBuildDir = rootProject.file("../linux/build-native")
val cppSourceDir = rootProject.file("app/src/main/cpp")

val buildLinuxNative by tasks.registering(Exec::class) {
    description = "Compiles liborgwritenotes_jni.so (Stylus Labs Write C++ core + Linux XInput2/evdev stylus bridge)"
    group = "build"
    inputs.dir(cppSourceDir)
    outputs.file(nativeBuildDir.resolve("liborgwritenotes_jni.so"))

    val sdkDir = System.getenv("ANDROID_HOME") ?: "/home/saisan/.android-sdk"
    val cmakeBin = listOf(
        File("$sdkDir/cmake/3.22.1/bin/cmake"),
        File("/usr/bin/cmake"),
        File("/usr/local/bin/cmake")
    ).firstOrNull { it.canExecute() }?.absolutePath ?: "cmake"

    commandLine(
        "bash", "-c",
        "\"$cmakeBin\" -S \"${cppSourceDir.absolutePath}\" -B \"${nativeBuildDir.absolutePath}\" -DCMAKE_BUILD_TYPE=Release && " +
            "\"$cmakeBin\" --build \"${nativeBuildDir.absolutePath}\" -j4"
    )
}

kotlin {
    jvmToolchain(17)
    sourceSets {
        val main by getting {
            kotlin.srcDirs("src/main/kotlin", "../app/src/main/java")
        }
        val test by getting {
            kotlin.srcDirs("src/test/kotlin")
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    exclude(
        "**/MainActivity.kt",
        "**/ChoreographerWatchdog.kt",
        "**/OnyxEInkBridge.kt",
        "**/AudioSketch.kt",
        "**/OfflineStore.kt"
    )
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")
    implementation("org.json:json:20231013")
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.orgwritenotes.LinuxMainKt"
        jvmArgs += listOf("-Djava.library.path=${nativeBuildDir.absolutePath}")

        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "org-writenotes"
            packageVersion = "0.1.0"
            description = "Org-WriteNotes Linux Stylus Companion for GNU Emacs"
            vendor = "OrgWriteNotes"
        }
    }
}

tasks.withType<JavaExec>().configureEach {
    dependsOn(buildLinuxNative)
    jvmArgs("-Djava.library.path=${nativeBuildDir.absolutePath}")
}

tasks.withType<Test>().configureEach {
    dependsOn(buildLinuxNative)
    systemProperty("java.library.path", nativeBuildDir.absolutePath)
}
