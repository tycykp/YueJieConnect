import java.security.MessageDigest

plugins { id("com.android.application") }
// The installed APK version alone cannot identify the DEX injected into a long-lived OEM
// process. Embed the exact production input snapshot so Binder can reject a cached module.
val runtimeInputDigest = MessageDigest.getInstance("SHA-256").apply {
    val runtimeFiles = rootProject.fileTree("android/module/src/main").files +
        rootProject.fileTree("core/src").files + rootProject.fileTree("native/src").files +
        rootProject.fileTree("transport/src").files + listOf(
            rootProject.file("android/module/build.gradle.kts"), rootProject.file("Cargo.lock"),
            rootProject.file("core/Cargo.toml"), rootProject.file("native/Cargo.toml"),
            rootProject.file("transport/Cargo.toml"))
    runtimeFiles.filter { it.isFile }.sortedBy { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }.forEach {
        update(it.relativeTo(rootProject.projectDir).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
        update(0.toByte())
        update(it.readBytes())
        update(0.toByte())
    }
}.digest().joinToString("") { "%02x".format(it) }
android {
    namespace = "io.github.qiangjian.connect"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"
    enableKotlin = false
    defaultConfig {
        applicationId = "io.github.qiangjian.connect"
        // This build is limited to the two Android 17 samples under review.
        minSdk = 37
        targetSdk = 37
        versionCode = 26
        versionName = "0.6.5-native-pairing"
        buildConfigField("String", "RUNTIME_INPUT_SHA", "\"$runtimeInputDigest\"")
        ndk { abiFilters += "arm64-v8a" }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").jniLibs.directories.add("build/generated/jniLibs")
    packaging { jniLibs { useLegacyPackaging = false } }
}
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    testImplementation("io.github.libxposed:api:102.0.0")
    testImplementation("junit:junit:4.13.2")
}
val buildRust = tasks.register<Exec>("buildRust") {
    workingDir(rootProject.projectDir)
    inputs.files(rootProject.fileTree("core/src"), rootProject.fileTree("native/src"), rootProject.fileTree("transport/src"),
        rootProject.file("Cargo.toml"), rootProject.file("Cargo.lock"),
        rootProject.file("core/Cargo.toml"), rootProject.file("native/Cargo.toml"),rootProject.file("transport/Cargo.toml"),
        rootProject.file("rust-toolchain.toml"), rootProject.file("scripts/build-native.ps1"))
    outputs.dir(layout.buildDirectory.dir("generated/jniLibs"))
    commandLine("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "scripts/build-native.ps1")
}
tasks.named("preBuild") { dependsOn(buildRust) }
configurations.configureEach { resolutionStrategy.failOnDynamicVersions() }
