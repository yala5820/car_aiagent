plugins {
    java
    application
    distribution
    alias(libs.plugins.objectbox)
}

group = "com.hirain.aiagent.rag.indexer"
version = "0.1.0-SNAPSHOT"

java {
    /**
     * Bundle 在桌面 Java 17 环境交付；显式 Toolchain 可阻止开发机的 Android/JDK 版本
     * 无意间成为离线构建产物的运行前提。
     */
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val sharedObjectBoxModelFile = layout.projectDirectory
    .dir("../../rag-schema/objectbox-models")
    .file("default.json")

sourceSets {
    named("main") {
        /**
         * `rag-schema` 是 CLI 与 Android 唯一的 Entity/协议源码。这里直接纳入当前
         * Java SourceSet，而非复制到 CLI，先验证 ObjectBox Plugin 能否处理共享源码。
         */
        java.srcDir(layout.projectDirectory.dir("../../rag-schema/src/main/java"))
        resources.srcDir(layout.projectDirectory.dir("../../rag-schema/contracts"))
    }
}

tasks.withType<JavaCompile>().configureEach {
    /**
     * Meta Model 也是跨端协议资产，必须写到共享目录并提交；不能让 CLI/Android 各自生成。
     */
    options.compilerArgs.add(
        "-Aobjectbox.modelPath=${sharedObjectBoxModelFile.asFile.absolutePath}"
    )
    options.compilerArgs.add("-Aobjectbox.myObjectBoxPackage=com.hirain.aiagent.rag.store")
    doFirst {
        /**
         * 首次初始化后禁止删除 Meta Model 再让 Processor 静默生成新 UID。若 Schema
         * 真的演进，必须显式编辑 Entity、保留旧 UID 并审阅提交的 default.json 差异。
         */
        check(sharedObjectBoxModelFile.asFile.isFile) {
            "缺少共享 ObjectBox Meta Model：${sharedObjectBoxModelFile.asFile}；禁止自动重建 UID。"
        }
    }
}

application {
    mainClass.set("com.hirain.aiagent.rag.indexer.RagIndexerMain")
}

/**
 * 此处仅接入已获项目负责人批准、且 G002 正在验证的候选依赖。PDFBox 保持 2.x，
 * 是为了先与 Tabula 1.0.5 的 PDFBox 2.x 传递依赖进行兼容性验证；不得由 Gradle
 * 自动升级到 PDFBox 3.x 后假定 Tabula 仍能正常工作。
 */
dependencies {
    implementation(libs.pdfbox)
    implementation(libs.tabula) {
        /**
         * V1 只处理文本型 PDF，扫描件与依赖图像解码的内容必须受控失败。
         * Tabula 的默认传递树会带入 JAI/JBIG2 图像组件，因此在依赖边界显式排除，
         * 避免它们被误认为是已支持的 OCR 或图像 PDF 能力。
         */
        exclude(group = "com.github.jai-imageio")
        exclude(group = "org.apache.pdfbox", module = "jbig2-imageio")
        exclude(group = "org.slf4j", module = "slf4j-simple")
    }
    implementation(libs.jsoup)
    implementation(libs.commonmark)
    implementation(libs.commonmark.gfm.tables)
    implementation(libs.jackson.databind)
    implementation(libs.picocli)
    implementation(libs.okhttp)
    /** Tabula 依赖 SLF4J API；显式 NOP binding 避免离线 CLI 在无日志后端时输出误导性警告。 */
    runtimeOnly(libs.slf4j.nop)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.jar {
    manifest {
        attributes["Implementation-Title"] = "rag-indexer"
        attributes["Implementation-Version"] = project.version
    }
}

/**
 * Phase 0 尚未引入测试框架依赖。此任务以 JDK 自带断言运行入口级 Smoke Test，
 * 让 `gradlew test` 在许可证 Gate 之前仍能验证 CLI 的公开行为，而不提前锁定测试库版本。
 */
val smokeTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "运行不依赖第三方测试框架的 RAG CLI Smoke Test。"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.hirain.aiagent.rag.indexer.RagIndexerMainTest")
    dependsOn(tasks.named("testClasses"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    dependsOn(smokeTest)
}

/**
 * 只在共享 Meta Model 已存在时生成初始跨端 Fixture。任务拒绝覆盖既有 Fixture，
 * 防止 Schema 变化后静默替换 Android 正在验证的二进制样本。
 */
tasks.register<Test>("generateObjectBoxFixture") {
    group = "verification"
    description = "生成由共享 Schema 驱动的最小 ObjectBox 跨端 Fixture。"
    dependsOn(tasks.named("testClasses"))
    useJUnitPlatform()
    include("**/ObjectBoxCrossRuntimeSpikeTest.class")
    systemProperty("rag.fixture.output", layout.projectDirectory.dir("../../rag-schema/test-fixtures/objectbox-v1").asFile.absolutePath)
    outputs.dir(layout.projectDirectory.dir("../../rag-schema/test-fixtures/objectbox-v1"))
}

/**
 * 生成 G404 的开发联调 Bundle。该任务使用测试中的固定 Mock 向量，报告必为 TEST_ONLY；
 * 任务拒绝覆盖既有目录，确保后续 Android 联调始终能追溯到同一份受控 Fixture。
 */
tasks.register<Test>("generateDevelopmentBundle") {
    group = "verification"
    description = "生成不可发布的三文件开发 RAG Bundle Fixture。"
    dependsOn(tasks.named("testClasses"))
    useJUnitPlatform()
    include("**/DevelopmentBuildPipelineTest.class")
    // 不声明 Gradle 输出目录：Test 任务会预创建声明目录，而 Pipeline 必须拒绝覆盖预存在 output。
    systemProperty("rag.development.bundle.output", layout.projectDirectory.dir("../../rag-schema/test-fixtures/development-v1/candidate-v1").asFile.absolutePath)
}
