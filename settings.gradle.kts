pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "AIAgent"
include(":app")
include(":http-client-ok")
include(":chat_memory_sqlite")
include(":android_document_loader")
include(":weatherutils")
includeBuild("build-logic")
include(":VehicleDoorManager")
include(":VehicleWindowManager")
include(":VehicleSeatManager")
include(":VehicleACController")
include(":VehicleAcManager")
include(":VehicleAcManager")
include(":VehicleFragManager")
include(":VlManager")
include(":SceneMatch")
include(":SceneServer")