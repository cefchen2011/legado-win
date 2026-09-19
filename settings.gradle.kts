pluginManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "legado-win"

// Android API 兼容层：让 legado 引擎源码无需修改即可在 JVM 上编译
include(":compat")
// 移植后的 legado 引擎（书源规则解析、网络、实体）
include(":engine")
// 端到端验证驱动（用真实书源跑通 搜索→详情→目录→正文）
include(":driver")
// Windows 应用外壳（本地 HTTP 服务 + Web UI）
include(":app")
