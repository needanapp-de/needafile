pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // only the PDF viewer and its Pdfium build come from JitPack
        maven {
            setUrl("https://www.jitpack.io")
            content { includeGroupByRegex("com\\.github\\.[Ii]nfomaniak") }
        }
    }
}
include(":app")

// 7-Zip-JBinding built from source with needafile's fix (see libs/7-zip-jbinding/README.md)
include(":sevenzipjbinding")
project(":sevenzipjbinding").projectDir = file("libs/7-zip-jbinding")

// needanapp design package (GPL-3.0), checked out next to this repo; provides de.needanapp:design. It has to be at least
// the version in DESIGN_VERSION (gradle.properties), so a build from the public sources names what is missing.
val designDir = file("../needanapp-design")
val designVersion = providers.gradleProperty("DESIGN_VERSION").get()
val designClone = "git clone --branch v$designVersion https://github.com/needanapp-de/needanapp-design.git ${designDir.canonicalPath}"
val designProperties = designDir.resolve("gradle.properties")
if (!designProperties.exists()) {
    throw GradleException("needafile needs the needanapp design package $designVersion next to this folder:\n$designClone")
}
val foundDesignVersion = java.util.Properties().apply { designProperties.reader().use { load(it) } }.getProperty("DESIGN_VERSION")
fun versionParts(version: String) = version.split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
val designTooOld = versionParts(foundDesignVersion).zip(versionParts(designVersion))
    .firstOrNull { (found, needed) -> found != needed }
    ?.let { (found, needed) -> found < needed } ?: false
if (designTooOld) {
    throw GradleException(
        "needafile needs the needanapp design package $designVersion or newer, ${designDir.canonicalPath} has $foundDesignVersion:\n" +
            "git -C ${designDir.canonicalPath} fetch --tags && git -C ${designDir.canonicalPath} checkout v$designVersion"
    )
}
includeBuild(designDir)
