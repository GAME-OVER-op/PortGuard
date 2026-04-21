import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePropsFile = rootDir.resolve("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.isFile) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val defaultStoreFile = rootDir.resolve(".tools/signing/debug.keystore")
val defaultStorePassword = "android"
val defaultKeyAlias = "androiddebugkey"
val defaultKeyPassword = "android"

val modulePropsFile = rootDir.parentFile.resolve("module.prop")
val moduleProps = Properties().apply {
    if (modulePropsFile.isFile) {
        modulePropsFile.inputStream().use { load(it) }
    }
}
val moduleVersionCode = (moduleProps.getProperty("versionCode") ?: "1").toIntOrNull() ?: 1
val moduleVersionName = moduleProps.getProperty("version") ?: "0.0.0"

android {
    namespace = "com.android.portguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.android.portguard"
        minSdk = 28
        targetSdk = 34
        versionCode = moduleVersionCode
        versionName = moduleVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("portguard") {
            storeFile = file(keystoreProps.getProperty("storeFile") ?: defaultStoreFile.absolutePath)
            storePassword = keystoreProps.getProperty("storePassword") ?: defaultStorePassword
            keyAlias = keystoreProps.getProperty("keyAlias") ?: defaultKeyAlias
            keyPassword = keystoreProps.getProperty("keyPassword") ?: defaultKeyPassword
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("portguard")
            isDebuggable = true
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("portguard")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
