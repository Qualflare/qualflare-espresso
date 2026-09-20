// Plugins are declared here and applied in the modules, so the root project stays
// configuration-only.
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}
