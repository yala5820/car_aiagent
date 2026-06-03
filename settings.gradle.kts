pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/central/")
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
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central/")
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
include(":VehicleChassisManager")

include(":VlManager")
include(":SceneMatch")
include(":SceneServer")
include(":ChatServer")
include(":SoaService")
include(":geoutils")