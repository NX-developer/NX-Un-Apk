plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nxdeveloper.unapk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nxdeveloper.unapk"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables {
            useSupportLibrary = true
        }
        resourceConfigurations += listOf("en", "tr")
    }

    signingConfigs {
        create("release") {
            storeFile = file("debug.keystore.placeholder")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/native-image/**",
                "META-INF/jadx/**",
                "META-INF/maven/**",
                "META-INF/services/javax.annotation.processing.Processor",
                "META-INF/*.kotlin_module",
                "**/module-info.class",
                "*.proto",
                "google/protobuf/**"
            )
            pickFirsts += listOf(
                "META-INF/services/jadx.plugins.input.dex.DexInput",
                "META-INF/services/org.jf.smali.SmaliPlugin"
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("io.github.skylot:jadx-core:1.5.5")
    implementation("io.github.skylot:jadx-dex-input:1.5.5")
    implementation("io.github.skylot:jadx-kotlin-metadata:1.5.5")

    implementation("io.github.reandroid:ARSCLib:1.3.8")

    implementation("org.slf4j:slf4j-api:2.0.16")
}

configurations.all {
    exclude(group = "ch.qos.logback")
    exclude(group = "org.slf4j", module = "slf4j-simple")
    exclude(group = "org.slf4j", module = "slf4j-log4j12")
    exclude(group = "com.google.code.findbugs", module = "jsr305")
}
