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

dependencies {
    // The reporter under test, by project reference rather than a published version.
    androidTestImplementation(project(":qualflare-espresso"))

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.monitor)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
