import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension

// 브라우저 시뮬레이터용 모듈. engine/src/main/kotlin 의 엔진 소스를 그대로 Kotlin/JS 로 컴파일한다(복사본 없음).
// 산출물: build/compileSync/js/main/productionExecutable/kotlin/kkaeugiSim.js (UMD 한 파일, 표준 라이브러리 포함)
// 앱 빌드(:app)와는 의존 관계가 없다.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    js(IR) {
        moduleName = "kkaeugiSim"
        browser()
        binaries.executable()
    }
    sourceSets {
        named("jsMain") {
            kotlin.srcDir("../engine/src/main/kotlin")
        }
    }
}

// Node 와 Yarn 을 내려받지 않고 시스템에 깔린 것을 쓴다(저장소 설정이 FAIL_ON_PROJECT_REPOS 라 내려받기용 저장소를 추가할 수 없다).
// 컴파일만 하므로(webpack 없음) npm 패키지 설치도 일어나지 않는다.
rootProject.plugins.withType<NodeJsRootPlugin> {
    rootProject.extensions.getByType<NodeJsRootExtension>().download = false
}
rootProject.plugins.withType<YarnPlugin> {
    rootProject.extensions.getByType<YarnRootExtension>().download = false
}
