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
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }
    }
}
rootProject.name = "kkaeugi"
include(":engine", ":app")
include(":websim") // 브라우저 시뮬레이터(엔진을 Kotlin/JS 로 컴파일). 앱 빌드와 무관
