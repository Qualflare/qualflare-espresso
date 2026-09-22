plugins {
    alias(libs.plugins.android.application)
}

// Never published. This module exists so the reporter can be instrumented on a
// real device, which is the only place the interesting failures live.
android {
    namespace = "com.qualflare.espresso.fixture"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qualflare.espresso.fixture"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The whole install story, in one line. Note it names the class as a STRING:
        // nothing references it, which is why consumer-rules.pro has to keep it.
        testInstrumentationRunnerArguments["listener"] =
            "com.qualflare.espresso.QualflareRunListener"
    }

    buildTypes {
        // The androidTest APK is minified on purpose in the `minified` variant:
        // R8 stripping a string-named listener is one of the three risks the
        // spike has to settle, and it is invisible unless something minifies.
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Normally the fixture tests the reporter in this repo. After a release it has to test the
// PUBLISHED artifact instead: resolving from Maven Central is the only thing that proves what a
// consumer actually gets -- the AAR's contents, its consumer ProGuard rules, and the version
// baked into it. -Pqualflare.usePublished=0.1.0 switches the source.
val publishedVersion: String? = providers.gradleProperty("qualflare.usePublished").orNull

dependencies {
    if (publishedVersion != null) {
        androidTestImplementation("com.qualflare:qualflare-espresso:$publishedVersion")
    } else {
        androidTestImplementation(project(":qualflare-espresso"))
    }

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.monitor)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
