import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(project(":engine"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.google.code.gson:gson:2.13.2")
    // WebDAV 同步用（engine 里 okhttp 是 implementation，不会传递过来）
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
}

application {
    mainClass = "legadowin.AppServerKt"
}
