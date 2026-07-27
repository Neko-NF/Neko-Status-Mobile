plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

val releaseTaskRequested = gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }
val officialCertificateSha256 = providers.gradleProperty("neko.officialCertificateSha256").orElse("").get()
val releaseSigning =
    mapOf(
        "path" to providers.environmentVariable("NEKO_KEYSTORE_PATH").orNull,
        "storePassword" to providers.environmentVariable("NEKO_KEYSTORE_PASSWORD").orNull,
        "keyAlias" to providers.environmentVariable("NEKO_KEY_ALIAS").orNull,
        "keyPassword" to providers.environmentVariable("NEKO_KEY_PASSWORD").orNull,
    )

if (releaseTaskRequested && releaseSigning.values.any { it.isNullOrBlank() }) {
    throw GradleException("Release signing requires NEKO_KEYSTORE_PATH, NEKO_KEYSTORE_PASSWORD, NEKO_KEY_ALIAS and NEKO_KEY_PASSWORD")
}

android {
    namespace = "com.nekonf.nekostatus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nekonf.nekostatus"
        minSdk = 31
        targetSdk = 36
        versionCode = 2_000_004
        versionName = "2.0.0-alpha.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "PRODUCTION_SERVER_URL", "\"https://nekostatus.koirin.com\"")
        buildConfigField("String", "UPDATE_PACKAGE_NAME", "\"com.nekonf.nekostatus\"")
        buildConfigField("String", "OFFICIAL_CERTIFICATE_SHA256", "\"$officialCertificateSha256\"")
    }

    signingConfigs {
        create("release") {
            releaseSigning["path"]?.let { storeFile = file(it) }
            storePassword = releaseSigning["storePassword"]
            keyAlias = releaseSigning["keyAlias"]
            keyPassword = releaseSigning["keyPassword"]
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
            manifestPlaceholders["usesCleartextTraffic"] = "true"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "false")
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:network"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:auth"))
    implementation(project(":feature:overview"))
    implementation(project(":feature:activity"))
    implementation(project(":feature:devices"))
    implementation(project(":feature:settings"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.work)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.core)
    implementation(libs.coil.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi.core)
    testImplementation(libs.roborazzi.compose)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
