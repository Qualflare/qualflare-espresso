pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "qualflare-espresso"

include(":qualflare-espresso")

// The fixture app exists to be instrumented, never to be published. Its tests are
// the only place the reporter meets a real device.
include(":fixture-app")
