plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Pleos(NaviHelper)가 기본이다. 일반 안드로이드 빌드가 필요하면 -PwithPleos=false
val withPleos = providers.gradleProperty("withPleos").orNull != "false"
val releaseStore = providers.environmentVariable("RADIO_KEYSTORE").orNull
android {
    namespace = "kr.changbak.radio"
    compileSdk = 36
    defaultConfig {
        applicationId = "kr.changbak.radio"
        minSdk = 28
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"
    }
    sourceSets["main"].assets.srcDir(rootProject.file("server/data"))
    if (withPleos) {
        sourceSets["main"].java.srcDir("src/pleos/java")
        sourceSets["main"].manifest.srcFile("src/pleos/AndroidManifest.xml")
    }
    if (releaseStore != null) {
        signingConfigs.create("submission") {
            storeFile = rootProject.file(releaseStore)
            storePassword = providers.environmentVariable("RADIO_STORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("RADIO_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("RADIO_KEY_PASSWORD").get()
        }
        buildTypes["release"].signingConfig = signingConfigs["submission"]
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(21) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")
    if (withPleos) implementation("ai.pleos.playground:NaviHelper:3.0.5")
    if (withPleos) implementation("ai.pleos.playground:TextToSpeech:3.3.1")
}



