plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// The Julius acoustic model is 12 MB and not ours, so it is fetched rather than committed.
val requiredDeps = listOf(
    layout.projectDirectory.file("src/main/assets/julius/model/jnas-tri-3k16-gid.binhmm").asFile,
    layout.projectDirectory.file("src/main/assets/julius/model/logicalTri-3k16-gid.bin").asFile,
)
val fetchDeps = tasks.register<Exec>("fetchDeps") {
    commandLine(rootDir.resolve("scripts/fetch-deps.sh").absolutePath)
    onlyIf { requiredDeps.any { !it.exists() } }
}

// scripts/julius-wake/ is the source of truth for the wake grammar. Only the automaton is bundled --
// the dictionary is written at runtime from whatever name Hachi has been given (see WakeGrammar).
val juliusGrammarDir = layout.buildDirectory.dir("generated/juliusGrammar")
val copyJuliusGrammar = tasks.register<Copy>("copyJuliusGrammar") {
    from(rootDir.resolve("scripts/julius-wake")) { include("wake.dfa") }
    into(juliusGrammarDir.map { it.dir("julius/grammar") })
}

android {
    namespace = "dev.polidog.hachi"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.polidog.hachi"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "armeabi-v7a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    sourceSets["main"].assets.srcDirs(juliusGrammarDir)
    // Android 10+'s W^X policy only lets a file be executed out of nativeLibraryDir. Legacy jniLibs
    // packaging is what makes libjulius-bin.so get extracted there on install instead of staying
    // zipped inside the APK, which is what lets WakeWord run it as a child process.
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    // The android.jar stub throws "not mocked" for JSONObject; unit tests need a real impl.
    testImplementation("org.json:json:20240303")
}

tasks.named("preBuild") { dependsOn(fetchDeps, copyJuliusGrammar) }
