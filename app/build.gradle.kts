import java.io.File

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val fetchDeps = tasks.register<Exec>("fetchDeps") {
    workingDir(rootDir)
    val script = rootDir.resolve("scripts/fetch-deps.sh").invariantSeparatorsPath
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        // Avoid Windows' WSL bash.exe; use Git for Windows, including per-user/custom installs.
        val override = providers.gradleProperty("butler.bash").orNull
            ?: providers.environmentVariable("BUTLER_BASH").orNull
        val candidates = listOfNotNull(
            System.getenv("ProgramFiles")?.let { file("$it/Git/bin/bash.exe") },
            System.getenv("ProgramFiles(x86)")?.let { file("$it/Git/bin/bash.exe") },
            System.getenv("LOCALAPPDATA")?.let { file("$it/Programs/Git/bin/bash.exe") },
        ) + System.getenv("PATH").orEmpty().split(File.pathSeparator).mapNotNull {
            val dir = file(it.trim('"'))
            if (dir.resolve("git.exe").isFile) dir.parentFile?.resolve("bin/bash.exe") else null
        }
        val bash = if (override != null) file(override) else candidates.firstOrNull { it.isFile }
        doFirst {
            check(bash?.isFile == true) {
                "Git Bash not found. Install Git for Windows or set BUTLER_BASH / -Pbutler.bash to its bash.exe path."
            }
        }
        commandLine(bash?.absolutePath ?: "bash.exe", script)
    } else {
        commandLine("bash", script)
    }
    // Always verify hashes: existence alone would accept truncated files or LFS pointers.
}

// scripts/julius-wake/ is the single source of truth for the Julius wake grammar; this task copies its
// compiled wake.dfa/wake.dict into a generated assets dir (added to the main sourceSet below) rather
// than committing a copy under app/src/main/assets/, matching how the Julius model itself is fetched
// (not committed) rather than checked in.
val juliusGrammarDir = layout.buildDirectory.dir("generated/juliusGrammar")
val copyJuliusGrammar = tasks.register<Copy>("copyJuliusGrammar") {
    from(rootDir.resolve("scripts/julius-wake")) {
        include("wake.dfa", "wake.dict")
    }
    into(juliusGrammarDir.map { it.dir("julius/grammar") })
}

android {
    namespace = "dev.syumai.butler"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.syumai.butler"
        minSdk = 30
        targetSdk = 35
        testInstrumentationRunner = "dev.syumai.butler.WakeInstrumentation"
        versionCode = 2
        versionName = "0.2.0"
        ndk { abiFilters += "armeabi-v7a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true } // for BuildConfig.DEBUG, used by the debug.butler.scene override
    sourceSets["main"].assets.srcDirs(juliusGrammarDir)
    // Android 10+'s W^X policy only allows executing files from nativeLibraryDir, not from app-writable
    // storage or straight out of the APK's zip -- legacy (uncompressed, extracted-on-install) jniLibs
    // packaging is what makes libjulius-bin.so land in nativeLibraryDir instead of staying zipped inside
    // the APK, which JuliusWakeDecoder needs in order to exec it as a child process.
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    // Real org.json impl for unit tests: the android.jar stub throws "not mocked" for JSONObject parsing.
    testImplementation("org.json:json:20240303")
}

tasks.named("preBuild") { dependsOn(fetchDeps, copyJuliusGrammar) }
