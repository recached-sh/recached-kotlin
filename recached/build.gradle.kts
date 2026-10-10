import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

val engineDir: File = rootProject.file("engine")

android {
    namespace = "dev.recached"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
        // Version-freshness checks depend on what was released today, not on
        // this change; dependency bumps are a separate decision.
        disable += setOf("NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion")
    }
}

androidComponents {
    onVariants { variant ->
        // The JVM suite is plain JUnit4 with nothing host-specific, so the
        // instrumented build runs it too: the same tests on the host and on a
        // device, where the Android libraries, JNA and ART are real.
        variant.androidTest?.sources?.kotlin?.addStaticSourceDirectory("src/test/kotlin")
    }
}

kotlin {
    explicitApi()
    compilerOptions { allWarningsAsErrors = true }
}

dependencies {
    // `implementation`: apps compile against this module's API only; the raw
    // generated layer stays off their classpath.
    implementation(project(":ffi"))
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.androidx.lifecycle.process)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // The plain JAR carries JNA's dispatch library for the host JVM; the AAR
    // :ffi depends on carries only the Android ones.
    testImplementation(libs.jna)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.okhttp.mockwebserver)
}

// The JVM tests load the host build of the core staged in engine/host, and run
// the live tests against the server binary staged beside it.
tasks.withType<Test>().configureEach {
    val host = engineDir.resolve("host")
    systemProperty("jna.library.path", host.path)
    systemProperty("recached.server", host.resolve("recached-server").path)
    doFirst {
        if (!host.resolve("recached-server").isFile) {
            throw GradleException(
                "Missing ${host.path}/recached-server and the host library. The release archive " +
                    "carries neither; run scripts/build-engine.sh (HOST_ONLY=1 is enough) for the tests.",
            )
        }
    }
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary(JavadocJar.Empty(), SourcesJar.Sources(), "release"))
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(artifactId = "recached-android")
    pom {
        name = "Recached for Android"
        description = "Local-first Recached client for Android: local reads, durable queued writes, " +
            "reconnect recovery and Flow-based updates."
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
}
