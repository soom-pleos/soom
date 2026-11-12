plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "ai.kkaeugi.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.kkaeugi.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 13
        versionName = "0.8.0"
        // 에뮬레이터(x86_64)와 실차 헤드유닛(arm64) 만 포함해 APK 를 줄인다. minify 는 켜지 않는다.
        ndk { abiFilters += listOf("x86_64", "arm64-v8a") }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // 공모전 제출용 release APK: 디버그 키로 서명해 바로 설치 가능하게 둔다
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module") }
}

// 화면 스크린샷(docs/shots/ 16장)은 `-Pscreenshots=true` 일 때만 찍는다: 그때만 Paparazzi 플러그인을 적용하고
// src/shots/ 의 ScreenshotTest 를 테스트 소스에 넣는다. 플래그 없이는 layoutlib 내려받기도, 테스트 컴파일도 없다.
val screenshots = providers.gradleProperty("screenshots").map { it == "true" }.getOrElse(false)
if (screenshots) {
    apply(plugin = "app.cash.paparazzi")
    android.sourceSets.getByName("test").java.srcDir("src/shots/java")
    tasks.withType<Test>().configureEach {
        systemProperty("kkaeugi.shotsDir", rootProject.file("docs/shots").absolutePath)
        systemProperty("kkaeugi.checkDir", layout.buildDirectory.dir("check-shots").get().asFile.absolutePath)
        // 한글 파일명(docs/shots/01_주행중_홈.png)이 깨지지 않게
        environment("LC_ALL", "C.UTF-8")
        jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8")
        maxHeapSize = "2g"
    }
}

dependencies {
    implementation(project(":engine"))

    // Pleos Connect SDK (공모전 제공 저장소)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:vehicle-data:1.1.11") // ErrorCode 등 상수 (Vehicle 의 runtime 의존을 compile 에 올림)
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // JVM 단위 테스트(RestStops 방향 매칭, AnswerListener 분류)
    testImplementation("junit:junit:4.13.2")
}
