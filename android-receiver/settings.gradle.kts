import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement { repositories { google(); maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }; maven { url = uri("https://maven.aliyun.com/repository/public") }; mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); maven { url = uri("https://maven.aliyun.com/repository/google") }; maven { url = uri("https://maven.aliyun.com/repository/public") }; mavenCentral() } }
rootProject.name = "airferry-lite-receiver"
include(":app")
