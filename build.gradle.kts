// Android Gradle Plugin 8.7.3 uchun Gradle 8.9 yoki undan yuqori kerak.
// Wrapper: gradle/wrapper/gradle-wrapper.properties (gradle-8.9-all.zip), gradlew skripti shuni ishlatadi.
// Agar baribir eski Gradle (masalan, 8.6) ishga tushsa, settings.gradle.kts AGP ni 8.4.2 ga tushiradi.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
