import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningPropertiesFile = File(System.getProperty("user.home"), ".android/reson-signing/signing.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) releaseSigningPropertiesFile.inputStream().use { load(it) }
}
val releaseStoreFile = releaseSigningProperties.getProperty("storeFile")?.let { path ->
    File(path).let { if (it.isAbsolute) it else File(releaseSigningPropertiesFile.parentFile, path) }
}
val releaseStorePassword = releaseSigningProperties.getProperty("storePassword")
val releaseKeyAlias = releaseSigningProperties.getProperty("keyAlias")
val releaseKeyPassword = releaseSigningProperties.getProperty("keyPassword")
val releaseSigningConfigured = releaseStoreFile?.isFile == true && !releaseStorePassword.isNullOrBlank() &&
    !releaseKeyAlias.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

android {
    namespace = "org.reson.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.reson.mobile"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "1.0.0"
    }
    signingConfigs {
        create("release") {
            storeFile = releaseStoreFile
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
}

// Do not let Android Gradle package an unsigned APK when release credentials are absent.
val validateReleaseSigning = tasks.register("validateReleaseSigning") {
    doLast {
        check(releaseSigningConfigured) {
            "The external release signing.properties file must contain a valid keystore path, store password, key alias, and key password."
        }
    }
}
tasks.configureEach {
    if (name == "packageRelease") dependsOn(validateReleaseSigning)
}
