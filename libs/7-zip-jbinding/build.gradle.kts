// 7-Zip-JBinding-4Android built from source, with needafile's fix for setting several archive properties (see README.md).
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "net.sf.sevenzipjbinding"
    compileSdk = libs.versions.app.build.compileSDKVersion.get().toInt()
    ndkVersion = libs.versions.sevenzipjbinding.ndk.get()
    defaultConfig {
        minSdk = libs.versions.app.build.minimumSDK.get().toInt()
    }
    compileOptions {
        val javaVersion = JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = libs.versions.sevenzipjbinding.cmake.get()
        }
    }
}
