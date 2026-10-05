plugins {
    application
}

group = "addetector"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.microsoft.playwright:playwright:1.63.0")
    implementation("info.picocli:picocli:4.7.7")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.networknt:json-schema-validator:1.5.9")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "addetector.App"
    applicationName = "ad-detector"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    // tool_version은 빌드 버전에서 한 곳으로만 관리한다.
    val toolVersion = project.version.toString()
    inputs.property("toolVersion", toolVersion)
    filesMatching("version.properties") {
        expand("toolVersion" to toolVersion)
    }
}

tasks.test {
    useJUnitPlatform {
        // 브라우저를 띄우는 테스트는 `gradlew browserTest`로 따로 돌린다.
        excludeTags("browser")
    }
    jvmArgs("-Dfile.encoding=UTF-8")
}

val browserTest = tasks.register<Test>("browserTest") {
    description = "브라우저(Playwright)를 띄우는 통합 테스트"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("browser")
    }
    jvmArgs("-Dfile.encoding=UTF-8")
    // 점검 중에는 브라우저를 내려받지 않는다(설계 G5).
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
}

// 모의 사이트를 점검해 data-expect-* 정답과 비교한다(설계 S4).
val evaluate = tasks.register<JavaExec>("evaluate") {
    description = "모의 사이트 precision/recall 측정"
    group = "verification"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "addetector.eval.Evaluate"
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
    args(
        "--site", layout.projectDirectory.dir("mock-site").asFile.path,
        "--out", layout.buildDirectory.dir("evaluate").get().asFile.path,
        "--history", layout.projectDirectory.file("eval/history.csv").asFile.path,
    )
}

tasks.named<JavaExec>("run") {
    jvmArgs("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    environment("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")
}

// 개발 중 java -cp "build/libs-all/*" 로 바로 돌려 볼 수 있게 런타임 클래스패스를 한 폴더에 모은다.
val copyRuntimeLibs = tasks.register<Sync>("copyRuntimeLibs") {
    from(configurations.runtimeClasspath)
    from(tasks.jar)
    into(layout.buildDirectory.dir("libs-all"))
}

// ───────────────────────── Windows 배포본 (설계 8. P0: JRE 포함 배포본)
//
//   gradlew bundle      → build/dist/ad-detector/  (폴더째 복사해 쓰는 배포본)
//   gradlew bundleZip   → build/dist/ad-detector-<버전>-windows.zip
//
// 배포본에는 Java 런타임(jlink), Playwright 드라이버(node.exe), 브라우저(Chromium headless shell)가 들어 있어
// 인터넷이 끊긴 Windows 11에서 폴더만으로 동작한다. 브라우저를 빼려면 -PwithoutBrowser (설치된 Edge → Chrome을 쓴다).

val jdkHome = javaToolchains.launcherFor(java.toolchain).map { it.metadata.installationPath.asFile }

// 실행에 필요한 JDK 모듈. jdk.charsets = 한글 콘솔(MS949), jdk.unsupported = Gson, jdk.zipfs = Playwright 드라이버 풀기
val runtimeModules = "java.base,java.logging,java.management,jdk.management,jdk.httpserver,jdk.unsupported,jdk.zipfs,jdk.charsets"

val jlinkRuntime = tasks.register<Exec>("jlinkRuntime") {
    description = "배포본에 넣을 최소 Java 런타임을 만든다"
    group = "distribution"
    val out = layout.buildDirectory.dir("runtime")
    inputs.property("modules", runtimeModules)
    outputs.dir(out)
    doFirst { delete(out) }
    executable = jdkHome.get().resolve("bin/jlink").path
    args(
        "--add-modules", runtimeModules,
        "--strip-debug", "--no-header-files", "--no-man-pages",
        "--output", out.get().asFile.path,
    )
}

// Playwright가 내려받아 둔 브라우저 폴더. 다른 곳이면 -PbrowsersDir=... 로 알려 준다.
val browsersDir: String = (findProperty("browsersDir") as String?)
    ?: System.getenv("PLAYWRIGHT_BROWSERS_PATH")
    ?: "${System.getenv("LOCALAPPDATA") ?: "."}/ms-playwright"

val installBrowsers = tasks.register<JavaExec>("installBrowsers") {
    description = "배포본에 넣을 Chromium headless shell을 내려받는다(개발 PC에서 한 번)"
    group = "distribution"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.microsoft.playwright.CLI"
    args("install", "--only-shell", "chromium")
}

val withoutBrowser = project.hasProperty("withoutBrowser")

val bundle = tasks.register<Sync>("bundle") {
    val browsersFrom = browsersDir
    description = "폴더째 복사해 쓰는 Windows 배포본을 만든다"
    group = "distribution"
    dependsOn(jlinkRuntime)
    into(layout.buildDirectory.dir("dist/ad-detector"))

    from("packaging") {
        include("*.bat")
        // cmd는 CRLF가 아닌 배치 파일을 잘못 읽을 수 있다.
        filter(org.apache.tools.ant.filters.FixCrLfFilter::class, "eol" to org.apache.tools.ant.filters.FixCrLfFilter.CrLf.newInstance("crlf"))
    }
    from("docs") {
        include("USER_GUIDE.md", "BUILD.md")
        into("docs")
    }
    from("LICENSE-THIRD-PARTY.md")
    into("lib") {
        // 드라이버는 풀어서 driver\ 에 두므로(시작할 때마다 임시 폴더에 푸는 시간을 없앤다) 묶음 jar는 넣지 않는다.
        from(configurations.runtimeClasspath) { exclude("driver-bundle-*.jar") }
        from(tasks.jar)
    }
    into("runtime") { from(jlinkRuntime) }
    into("driver") {
        from({ configurations.runtimeClasspath.get().filter { it.name.startsWith("driver-bundle-") }.map { zipTree(it) } }) {
            include("driver/win32_x64/**")
            // 여기서 path는 배포 폴더 기준이다: driver/driver/win32_x64/node.exe → driver/node.exe
            eachFile { path = path.replace("driver/win32_x64/", "") }
        }
        from({ configurations.runtimeClasspath.get().filter { it.name.startsWith("driver-") && !it.name.startsWith("driver-bundle-") }.map { zipTree(it) } }) {
            include("driver/package/**")
            eachFile { path = path.replaceFirst("driver/driver/", "driver/") }
        }
        includeEmptyDirs = false
    }
    // 담당자가 고칠 수 있는 키워드 사전(conf\ 에 있으면 내장 사전 대신 읽는다)
    into("conf") { from("src/main/resources") { include("keywords_*.txt") } }
    if (!withoutBrowser) {
        into("browsers") {
            from(browsersDir) { include("chromium_headless_shell-*/**", "winldd-*/**") }
        }
    }
    doLast {
        val shell = destinationDir.resolve("browsers").listFiles { f -> f.name.startsWith("chromium_headless_shell-") }
        if (!withoutBrowser && shell.isNullOrEmpty()) {
            logger.warn("경고: $browsersFrom 에 chromium_headless_shell-* 이 없어 브라우저를 넣지 못했습니다. gradlew installBrowsers 후 다시 묶으세요. (없어도 설치된 Edge/Chrome으로 동작합니다)")
        }
    }
}

val bundleZip = tasks.register<Zip>("bundleZip") {
    description = "배포본을 zip으로 묶는다"
    group = "distribution"
    from(bundle) { into("ad-detector") }
    destinationDirectory = layout.buildDirectory.dir("dist")
    archiveFileName = "ad-detector-${project.version}-windows.zip"
}
