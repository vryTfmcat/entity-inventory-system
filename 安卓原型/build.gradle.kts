plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.24" apply false
}

// Generated output belongs outside the synchronized vault on every device.
val externalBuildRoot = System.getenv("ENTITY_INVENTORY_BUILD_ROOT")?.let { file(it) }
    ?: java.io.File(System.getProperty("user.home"), "Documents/Codex/build-cache/entity-inventory")
allprojects {
    val segment = project.path.removePrefix(":").replace(':', '/').ifEmpty { "_root" }
    layout.buildDirectory.set(java.io.File(externalBuildRoot, "build/$segment"))
}
