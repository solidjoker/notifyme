// 仓库与插件解析配置：优先 google()（AGP / androidx 都在这里），mavenCentral() 兜底
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

rootProject.name = "notifyme"
include(":app")
