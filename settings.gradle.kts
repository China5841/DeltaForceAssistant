pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.application" || 
                requested.id.id == "com.android.library") {
                useModule("com.android.tools.build:gradle:8.2.2")
            }
            if (requested.id.id == "org.jetbrains.kotlin.android") {
                useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.20")
            }
            if (requested.id.id == "com.google.dagger.hilt.android") {
                useModule("com.google.dagger:hilt-android-gradle-plugin:2.48")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://mirrors.tuna.tsinghua.edu.cn/maven")
        maven("https://maven.aliyun.com/repository/public")
    }
}
rootProject.name = "DeltaForceMobile"
include(":app")