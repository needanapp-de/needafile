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

// needanapp design package (GPL-3.0), checked out next to this repo; provides de.needanapp:design
includeBuild("../needanapp-design")
