import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// 分层约定：
//   src/main/kotlin   —— 原样移植的上游 legado 源码（由 tools/port_sync.py 同步，勿手改）
//   src/desktop/kotlin —— 桌面端手写的替代实现（Android 专属设施的 JVM 版本）
sourceSets {
    main {
        kotlin.srcDir("src/desktop/kotlin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    api(project(":compat"))

    // 与上游 legado-plus 保持一致的技术栈与版本（见 gradle/libs.versions.toml）
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // 网络
    implementation("com.squareup.okhttp3:okhttp:5.3.2")

    // HTML / XML / JSON 解析（书源规则的核心）
    implementation("org.jsoup:jsoup:1.16.2")
    implementation("cn.wanghaomiao:JsoupXpath:2.5.3")
    implementation("com.jayway.jsonpath:json-path:2.10.0")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("org.apache.commons:commons-text:1.13.1")

    // 书源 JS 规则引擎
    implementation("org.mozilla:rhino:1.8.1")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // 加密（书源签名/哈希）
    implementation("cn.hutool:hutool-crypto:5.8.22")

    // Android 内置 org.json / org.xmlpull，桌面端需要显式依赖
    // （org.xmlpull 用于 epublib 解析 EPUB 的 OPF/NCX）
    implementation("org.json:json:20240303")
    implementation("net.sf.kxml:kxml2:2.3.0")

    // 简繁转换（上游 ChineseUtils 使用，来自 jitpack）
    implementation("com.github.liuyueyi.quick-chinese-transfer:quick-transfer-core:0.2.17")
}
