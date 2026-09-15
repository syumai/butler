plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val requiredDeps = listOf(
    layout.projectDirectory.file("src/main/assets/vosk/vosk-model-small-ja-0.22/am/final.mdl").asFile,
    layout.projectDirectory.file("src/main/assets/julius/model/jnas-tri-3k16-gid.binhmm").asFile,
    layout.projectDirectory.file("src/main/assets/julius/model/logicalTri-3k16-gid.bin").asFile,
)
val fetchDeps = tasks.register<Exec>("fetchDeps") {
    commandLine(rootDir.resolve("scripts/fetch-deps.sh").absolutePath)
    onlyIf { requiredDeps.any { !it.exists() } }
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
