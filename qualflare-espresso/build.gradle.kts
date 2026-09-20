plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.qualflare.espresso"
    compileSdk = 36

    defaultConfig {
        // 24, not 21: java.util.function is available here, which the ported code
        // uses. java.time, java.util.Base64 and all of java.nio.file are API 26+
        // and are deliberately absent from src/main -- see the lint config below,
        // which makes reintroducing them a build failure rather than a runtime
        // crash on someone's phone.
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        // Java 11 bytecode, matching the two JVM sibling reporters.
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        // NewApi as an error is the guard rail for minSdk 24. Core library
        // desugaring would hide these, but that is a CONSUMER setting an AAR
        // cannot assume, so the library must not rely on it.
        error += "NewApi"
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    publishing {
        singleVariant("release") {
            // AGP's own replacements for maven-source-plugin / maven-javadoc-plugin,
            // which do not carry over from the Maven siblings.
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// CI compiles against the androidx.test floor as well as the current version:
// -Pqualflare.monitorVersion=1.4.0. 1.4.0 is the first release carrying
// androidx.test.platform.io.PlatformTestStorage, and since the library's
// dependencies are compileOnly, whatever the consumer resolves is what runs.
val monitorOverride: String? = providers.gradleProperty("qualflare.monitorVersion").orNull

configurations.configureEach {
    if (monitorOverride != null) {
        resolutionStrategy.eachDependency {
            if (requested.group == "androidx.test" && requested.name == "monitor") {
                useVersion(monitorOverride)
                because("CI floor check: -Pqualflare.monitorVersion")
            }
        }
    }
}

dependencies {
    // compileOnly throughout, the AAR-shaped equivalent of the siblings' <scope>provided</scope>.
    // Publishing a version constraint on androidx.test would invite NoSuchMethodError
    // against whatever Espresso BOM the consumer already has.
    compileOnly(libs.junit)
    compileOnly(libs.androidx.test.monitor)
    compileOnly(libs.androidx.test.runner)
    compileOnly(libs.espresso.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.monitor)
    testImplementation(libs.androidx.test.runner)
    testImplementation(libs.espresso.core)
}
