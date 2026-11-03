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
        // Also hosts the SDK's transitive third-party deps (mmkv, easypermissions, hugo, ...).
        maven { url = uri("https://repo.boox.com/repository/maven-public/") }
    }
}

rootProject.name = "OrgWriteNotes"
include(":app")

