plugins {
    java
    application
}

group = "com.aceminerojal"
version = "beta-0.1"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val lwjglVersion = "3.3.3"

val lwjglNatives = mapOf(
    "lwjgl" to listOf("linux", "windows", "macos", "macos-arm64"),
    "lwjgl-glfw" to listOf("linux", "windows", "macos", "macos-arm64"),
    "lwjgl-vulkan" to listOf("macos", "macos-arm64"),
    "lwjgl-shaderc" to listOf("linux", "windows", "macos", "macos-arm64"),
    "lwjgl-stb" to listOf("linux", "windows", "macos", "macos-arm64"),
    "lwjgl-openal" to listOf("linux", "windows", "macos", "macos-arm64"),
)

dependencies {
    lwjglNatives.forEach { (name, platforms) ->
        implementation("org.lwjgl:$name:$lwjglVersion")
        platforms.forEach { p ->
            runtimeOnly("org.lwjgl:$name:$lwjglVersion:natives-$p")
        }
    }

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "main.Main"
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "main.Main"
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
