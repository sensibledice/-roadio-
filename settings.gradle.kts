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
        if (providers.gradleProperty("withPleos").orNull != "false") {
            maven("https://nexus-playground.pleos.ai/repository/maven-releases/") {
                content { includeGroup("ai.pleos.playground") }
            }
        }
    }
}
rootProject.name = "ChangbakRadio"
include(":app")

