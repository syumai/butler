plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val wakeAssetsDir = layout.projectDirectory.dir("src/main/assets/wake")
val requiredDeps = listOf(
    layout.projectDirectory.file("libs/sherpa-onnx-1.12.14.aar").asFile,
    wakeAssetsDir.file("encoder.onnx").asFile,
    wakeAssetsDir.file("decoder.onnx").asFile,
    wakeAssetsDir.file("joiner.onnx").asFile,
    layout.projectDirectory.file("src/main/assets/vosk/vosk-model-small-ja-0.22/am/final.mdl").asFile,
)
val fetchDeps = tasks.register<Exec>("fetchDeps") {
    commandLine(rootDir.resolve("scripts/fetch-deps.sh").absolutePath)
    onlyIf { requiredDeps.any { !it.exists() } }
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
}
dependencies {
    implementation(files("libs/sherpa-onnx-1.12.14.aar"))
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    // Real org.json impl for unit tests: the android.jar stub throws "not mocked" for JSONObject parsing.
    testImplementation("org.json:json:20240303")
}

tasks.named("preBuild") { dependsOn(fetchDeps) }
