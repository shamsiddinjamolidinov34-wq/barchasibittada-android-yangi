pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    // Telegram bot orqali yig'ilganda GitHub workflowi ba'zan eski Gradle (8.6) bilan ishlaydi.
    // Android Gradle Plugin 8.7.3 kamida Gradle 8.9 talab qiladi; Gradle 8.6 uchun esa AGP 8.4.2 mos.
    // Shuning uchun AGP versiyasi ishlayotgan Gradle ga qarab tanlanadi:
    //   Gradle 8.9 va undan yuqori -> AGP 8.7.3 (build.gradle.kts dagi versiya)
    //   Gradle 8.9 dan eski        -> AGP 8.4.2
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.application" &&
                org.gradle.util.GradleVersion.current() < org.gradle.util.GradleVersion.version("8.9")
            ) {
                useVersion("8.4.2")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "barchasibittada"
include(":app")
