plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    application
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        allWarningsAsErrors = true
    }
}

version =
    groovy.json
        .JsonSlurper()
        .parse(file("package.json"))
        .let { (it as Map<*, *>)["version"] as String }

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}

application {
    mainClass = "io.github.pawelwywiol.minervaweb.MainKt"
}

ktlint {
    version = libs.versions.ktlint.cli
}

val torrentNative = configurations.create("torrentNative")

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.libtorrent4j)
    runtimeOnly(libs.slf4j.simple)
    torrentNative(libs.libtorrent4j.linux)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

interface InjectedArchives {
    @get:Inject val archives: ArchiveOperations
}

// libtorrent4j loads its JNI library from a path, it does not unpack it from the jar
val extractTorrentNative =
    tasks.register<Sync>("extractTorrentNative") {
        val archives = objects.newInstance<InjectedArchives>().archives
        from(torrentNative.elements.map { jars -> jars.map { archives.zipTree(it.asFile) } }) {
            include("lib/x86_64/libtorrent4j.so")
        }
        into(layout.buildDirectory.dir("native"))
    }

distributions.main {
    contents { from(extractTorrentNative) { into("native") } }
}

tasks.test {
    useJUnitPlatform()
    dependsOn(extractTorrentNative)
    val native = layout.buildDirectory.file("native/lib/x86_64/libtorrent4j.so")
    jvmArgumentProviders += CommandLineArgumentProvider { listOf("-Dlibtorrent4j.jni.path=${native.get().asFile}") }
}
