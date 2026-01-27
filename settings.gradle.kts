pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
    }
}

@file:Suppress("ktlint:standard:property-naming")
val TRANSLATIONS_ONLY: String? by settings

@Suppress("ktlint:standard:property-naming")
val CONNECTBOT_USE_LOCAL_TERMLIB: String? by settings

fun String?.isTruthy(): Boolean =
    when (this?.trim()?.lowercase()) {
        "1",
        "true",
        "yes",
        "on",
        -> true
        else -> false
    }

if (System.getenv("CONNECTBOT_USE_LOCAL_TERMLIB").isTruthy() || CONNECTBOT_USE_LOCAL_TERMLIB.isTruthy()) {
    val localTermlibDir = file("../termlib")
    if (org.gradle.util.GradleVersion.current() < org.gradle.util.GradleVersion.version("9.1")) {
        println(
            "CONNECTBOT_USE_LOCAL_TERMLIB is set, but this build uses Gradle ${org.gradle.util.GradleVersion.current()} and cannot include ../termlib (requires Gradle 9.1+). " +
                "Publish termlib to mavenLocal and use -PconnectbotTermlibVersion=<version> instead."
        )
    } else if (localTermlibDir.isDirectory) {
        includeBuild(localTermlibDir) {
            dependencySubstitution {
                substitute(module("org.connectbot:termlib")).using(project(":lib"))
            }
        }
    }
}

if (TRANSLATIONS_ONLY.isNullOrBlank()) {
    include(":app")
}
include(":translations")
