pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "rag-indexer"

/**
 * 该 Settings 刻意保持独立：离线索引器不能成为 Android 根工程的子模块，
 * 从而避免 Android SDK、AAR 与桌面 JVM CLI 的依赖和任务图彼此污染。
 */
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}
