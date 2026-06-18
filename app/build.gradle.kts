import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.wdtt.client"
    compileSdk = 35

    val localProperties = Properties()
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.inputStream())
    }
    val hubMosToken = localProperties.getProperty("HUB_MOS_TOKEN")
        ?: System.getenv("HUB_MOS_TOKEN")
        ?: ""

    defaultConfig {
        applicationId = "com.bypassme.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 17
        versionName = "1.1.6"

        buildConfigField("String", "HUB_MOS_TOKEN", "\"$hubMosToken\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }


    signingConfigs {
        create("release") {
            val keyFile = localProperties.getProperty("KEYSTORE_FILE")
            if (keyFile != null) {
                // Резолвим путь: если начинается с "..", берём от корня проекта
                val resolvedFile = if (keyFile.startsWith("..")) {
                    // ../release.keystore -> корень проекта / release.keystore
                    file(rootDir.resolve(keyFile.substring(3)))
                } else {
                    file(keyFile)
                }
                if (resolvedFile.exists()) {
                    storeFile = resolvedFile
                    storePassword = localProperties.getProperty("KEYSTORE_PASSWORD")
                    keyAlias = localProperties.getProperty("KEY_ALIAS")
                    keyPassword = localProperties.getProperty("KEY_PASSWORD")
                } else {
                    println("WARNING: Keystore file not found: $keyFile (resolved: ${resolvedFile.absolutePath})")
                }
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keyFile = localProperties.getProperty("KEYSTORE_FILE")
            val resolvedFile = if (keyFile != null && keyFile.startsWith("..")) {
                file(rootDir.resolve(keyFile.substring(3)))
            } else if (keyFile != null) {
                file(keyFile)
            } else null
            
            if (resolvedFile != null && resolvedFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
                println("✅ Signing config applied: ${resolvedFile.absolutePath}")
            } else {
                println("⚠️ WARNING: Keystore not found, using debug signing")
                println("   Looked for: ${resolvedFile?.absolutePath ?: keyFile}")
            }
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            jniLibs.setSrcDirs(listOf("src/main/jniLibs"))
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation("com.github.mwiede:jsch:0.2.16")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
