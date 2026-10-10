import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

// The UniFFI layer: generated Kotlin bindings and the native libraries they
// load, both staged in engine/ by scripts/build-engine.sh or
// scripts/fetch-engine.sh. Nothing here is written by hand, which is why it
// is its own module: it compiles without :recached's explicit-API mode and
// warnings-as-errors, which generated code cannot meet.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

val engineDir: File = rootProject.file("engine")

android {
    namespace = "dev.recached.ffi"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // Generated code: lint findings here are UniFFI's, reported upstream
        // rather than failing this build.
        abortOnError = false
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
        // The core ships arm64-v8a, armeabi-v7a and x86_64 only.
        disable += "ChromeOsAbiSupport"
    }
}

// Added through the variant API: AGP 9's DSL source sets no longer take srcDir().
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(engineDir.resolve("jniLibs").path)
        variant.sources.kotlin?.addStaticSourceDirectory(engineDir.resolve("kotlin").path)
    }
}

dependencies {
    // `api`: the generated classes extend JNA types, so anything compiling
    // against them needs JNA too. The AAR carries the Android dispatch libraries.
    api("${libs.jna.get()}@aar")
}

val checkEngine = tasks.register("checkEngine") {
    description = "Fails with instructions when engine/ has not been staged."
    val bindings = engineDir.resolve("kotlin/dev/recached/ffi/recached_mobile.kt")
    doLast {
        if (!bindings.isFile) {
            throw GradleException(
                "Missing ${bindings.path}. Stage the engine first:\n" +
                    "  scripts/build-engine.sh [../recached]   build from a local checkout\n" +
                    "  scripts/fetch-engine.sh                 download the pinned release",
            )
        }
    }
}
tasks.named("preBuild") { dependsOn(checkEngine) }

mavenPublishing {
    configure(AndroidSingleVariantLibrary(JavadocJar.Empty(), SourcesJar.Sources(), "release"))
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(artifactId = "recached-android-ffi")
    pom {
        name = "Recached for Android — native core"
        description = "UniFFI bindings and native libraries for recached-android. Not an API: depend on recached-android."
        recachedPom()
    }
}

fun org.gradle.api.publish.maven.MavenPom.recachedPom() {
    url = "https://github.com/recached-sh/recached-kotlin"
    licenses {
        license {
            name = "Apache-2.0"
            url = "https://www.apache.org/licenses/LICENSE-2.0"
        }
    }
    developers {
        developer {
            id = "recached-sh"
            name = "Recached"
            url = "https://github.com/recached-sh"
        }
    }
    scm {
        url = "https://github.com/recached-sh/recached-kotlin"
        connection = "scm:git:https://github.com/recached-sh/recached-kotlin.git"
        developerConnection = "scm:git:ssh://git@github.com/recached-sh/recached-kotlin.git"
    }
}
