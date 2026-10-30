import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.konan.properties.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlin.compose)
}

// needafile's keys live outside the repo and outside Syncthing, in ~/needanapp-signing (or the folder set as
// needanapp.signingDir in ~/.gradle/gradle.properties); a keystore.properties in the project root still wins.
// releases are signed with the key of the stable repository apps.needanapp.de in the subfolder stabil/; the folder
// itself holds the older key that test builds of the other apps still use
val signingDir = File(providers.gradleProperty("needanapp.signingDir").orNull ?: "${System.getProperty("user.home")}/needanapp-signing")
val keystorePropertiesFile: File = rootProject.file("keystore.properties").takeIf { it.exists() } ?: File(signingDir, "stabil/keystore.properties")
val uploadPropertiesFile = File(signingDir, "keystore-upload.properties")

// storeFile may be relative to the properties file
fun com.android.build.api.dsl.ApkSigningConfig.readFrom(propertiesFile: File) {
    val properties = Properties().apply { FileInputStream(propertiesFile).use { load(it) } }
    keyAlias = properties.getProperty("keyAlias")
    keyPassword = properties.getProperty("keyPassword")
    storeFile = propertiesFile.parentFile.resolve(properties.getProperty("storeFile"))
    storePassword = properties.getProperty("storePassword")
}

fun hasSigningVars(): Boolean {
    return providers.environmentVariable("SIGNING_KEY_ALIAS").orNull != null
            && providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull != null
            && providers.environmentVariable("SIGNING_STORE_FILE").orNull != null
            && providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull != null
}

base {
    val versionCode = project.property("VERSION_CODE").toString().toInt()
    archivesName = "needafile-$versionCode"
}

android {
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = project.property("APP_ID").toString()
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = project.libs.versions.app.build.targetSDK.get().toInt()
        versionName = project.property("VERSION_NAME").toString()
        versionCode = project.property("VERSION_CODE").toString().toInt()
        multiDexEnabled = true
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            register("release") { readFrom(keystorePropertiesFile) }
        } else if (hasSigningVars()) {
            register("release") {
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
                storeFile = file(providers.environmentVariable("SIGNING_STORE_FILE").get())
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
            }
        } else {
            logger.warn("Warning: No signing config found. Build will be unsigned.")
        }
        // the Play upload key signs the app bundle; Play App Signing then signs the app with the release key
        if (uploadPropertiesFile.exists()) {
            register("upload") { readFrom(uploadPropertiesFile) }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // the permission guarding the exchange of connections with needaload (exchange/ConnectionExchange.kt); debug and
    // release builds are signed with different keys, so they need different names, or installing both fails
    // (INSTALL_FAILED_DUPLICATE_PERMISSION). needaload uses the same names.
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["sharePermission"] = "de.needanapp.permission.SHARE_CONNECTIONS.debug"
        }
        release {
            manifestPlaceholders["sharePermission"] = "de.needanapp.permission.SHARE_CONNECTIONS"
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // signed per flavor, see productFlavors
        }
    }

    flavorDimensions.add("variants")
    productFlavors {
        // F-Droid and direct installs carry the release key, the Play bundle the upload key; debug builds keep the debug key
        register("core") { signingConfig = signingConfigs.findByName("release") }
        register("foss") { signingConfig = signingConfigs.findByName("release") }
        register("gplay") { signingConfig = signingConfigs.findByName("upload") }
    }

    sourceSets {
        getByName("main").java.directories.add("src/main/kotlin")
    }

    compileOptions {
        val currentJavaVersionFromLibs = JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    // the vault tests run on the computer; Android calls in passing (progress timing) answer with defaults there
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    dependenciesInfo {
        includeInApk = false
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
        // only English and German are maintained
        localeFilters += listOf("en", "de")
    }

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(project.libs.versions.app.build.kotlinJVMTarget.get())
        )
    }

    namespace = project.property("APP_ID").toString()

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = false
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    // sshj/SMBJ pull in BouncyCastle; avoid duplicate META-INF entries when packaging.
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/*/OSGI-INF/MANIFEST.MF",
                "META-INF/LICENSE.md",
                "META-INF/BC1024KE.SF",
                "META-INF/BC1024KE.DSA",
                "META-INF/BC2048KE.SF",
                "META-INF/BC2048KE.DSA",
            )
        }
    }
}

/**
 * Writes the resolved version of every library packed into the APK to assets/about/library_versions.txt
 * (one group:name=version per line), so the about screen shows the versions that are really shipped.
 */
abstract class GenerateLibraryVersionsTask : DefaultTask() {
    @get:Input
    abstract val rootComponent: Property<ResolvedComponentResult>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val versions = sortedMapOf<String, String>()
        val visited = mutableSetOf<Any>()
        val queue = ArrayDeque(listOf(rootComponent.get()))
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            if (!visited.add(component.id)) continue
            (component.id as? ModuleComponentIdentifier)?.let { versions["${it.group}:${it.module}"] = it.version }
            component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue.add(it.selected) }
        }

        val file = outputDirectory.file("about/library_versions.txt").get().asFile
        file.parentFile.mkdirs()
        file.writeText(versions.entries.joinToString(separator = "\n", postfix = "\n") { "${it.key}=${it.value}" })
    }
}

androidComponents {
    onVariants { variant ->
        val task = tasks.register<GenerateLibraryVersionsTask>("generate${variant.name.replaceFirstChar { it.uppercase() }}LibraryVersions") {
            rootComponent.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, GenerateLibraryVersionsTask::outputDirectory)
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.zip4j)
    implementation(libs.sshj)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.smbj)
    implementation(libs.dcerpc)
    implementation(libs.okhttp)
    implementation(libs.commons.net)
    implementation(project(":sevenzipjbinding"))
    implementation(libs.xz)
    implementation(libs.cryptolib)
    runtimeOnly(libs.slf4j.nop)
    implementation(libs.needanapp.design)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.cryptofs)
    testImplementation(libs.orgjson)

    // image viewer and editor
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.jxl.coder.coil)
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.android.image.cropper)
    implementation(libs.androidx.exifinterface)
    // document viewer
    implementation(libs.android.pdf.viewer)
    implementation(libs.commonmark)
    implementation(libs.commonmark.autolink)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.task.lists)
    implementation(libs.jsoup)
    implementation(libs.commons.csv)
}
