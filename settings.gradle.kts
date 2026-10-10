pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "recached-kotlin"
// :ffi is the UniFFI-generated layer (Kotlin bindings, native libraries, JNA);
// :recached is the public API built on it. Separate modules so the strict
// compiler settings apply to hand-written code: generated code cannot meet
// explicit-API mode, and UniFFI has no option to emit `internal`.
include(":ffi", ":recached")
// A small checklist app that shows the SDK's four promises; not published.
include(":demo")
