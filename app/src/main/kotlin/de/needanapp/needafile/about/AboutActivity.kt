package de.needanapp.needafile.about

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ContentRow
import de.needanapp.design.components.GroupRowSurface
import de.needanapp.design.components.InfoDialog
import de.needanapp.design.components.ListRow
import de.needanapp.design.components.RowPosition
import de.needanapp.design.components.SettingsGroup
import de.needanapp.design.components.SubScreen
import de.needanapp.design.components.scrollbar
import de.needanapp.needafile.BuildConfig
import de.needanapp.needafile.R
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.openWebPage
import de.needanapp.needafile.core.writeEmail
import de.needanapp.needafile.system.LauncherIcon
import de.needanapp.needafile.theme.setThemedContent

/**
 * The app's own about screen: identity from branding.xml, its license and the projects it builds on, with version and
 * license badges in the style of shields.io.
 */
class AboutActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setThemedContent { AboutScreen(this, onBack = ::finish) }
    }
}

private data class ShownLicense(val license: SourceLicense, val source: AboutSource?)

@Composable
private fun AboutScreen(activity: BaseActivity, onBack: () -> Unit) {
    val versions = remember { libraryVersions(activity) }
    var shownLicense by remember { mutableStateOf<ShownLicense?>(null) }

    fun showLicense(license: SourceLicense, source: AboutSource? = null) {
        // licenses without a bundled text open the project's own license page
        if (license.assetName == null) activity.openWebPage(source?.licenseUrl ?: license.url) else shownLicense = ShownLicense(license, source)
    }

    SubScreen(stringResource(R.string.about), onBack) { padding ->
        val listState = rememberLazyListState()
        LazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxSize().scrollbar(listState, padding)) {
            item { Header(onLicenseClick = { showLicense(APP_LICENSE) }) }

            item {
                val links = listOf(
                    Triple(Icons.Outlined.Link, R.string.about_website, R.string.brand_website_url),
                    Triple(Icons.Outlined.Storefront, R.string.about_fdroid, R.string.brand_fdroid_url),
                    Triple(Icons.Outlined.PrivacyTip, R.string.about_privacy, R.string.brand_privacy_url),
                    Triple(Icons.Outlined.Code, R.string.about_source_code, R.string.brand_source_code_url),
                    Triple(Icons.Outlined.Mail, R.string.about_contact, R.string.brand_contact_email),
                ).map { (icon, label, target) -> Triple(icon, label, stringResource(target)) }.filter { it.third.isNotBlank() }
                if (links.isNotEmpty()) {
                    SettingsGroup(null) {
                        links.forEach { (icon, label, target) ->
                            row {
                                // query parameters (e.g. the repository fingerprint) are only for the opening app
                                ListRow(it, stringResource(label), summary = target.substringBefore('?'), icon = icon, onClick = {
                                    if (label == R.string.about_contact) activity.writeEmail(target) else activity.openWebPage(target)
                                })
                            }
                        }
                    }
                }
            }

            item {
                SettingsGroup(stringResource(R.string.about_license)) {
                    row {
                        ContentRow(it, null) {
                            Text(
                                stringResource(R.string.about_license_notice, stringResource(R.string.brand_name)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    row { ListRow(it, stringResource(R.string.about_show_license_text), icon = Icons.AutoMirrored.Outlined.Article, onClick = { showLicense(APP_LICENSE) }) }
                }
            }

            item {
                SettingsGroup(stringResource(R.string.about_sources)) {
                    SOURCES.forEach { source -> row { SourceRow(it, source, versions, activity, ::showLicense) } }
                }
            }

            item {
                SettingsGroup(stringResource(R.string.about_libraries)) {
                    LIBRARIES.forEach { source -> row { SourceRow(it, source, versions, activity, ::showLicense) } }
                }
            }

            item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
        }
    }

    shownLicense?.let { (license, source) ->
        val text = remember(license, source) { licenseText(activity, license, source?.copyright) }
        InfoDialog(title = license.spdxId, message = text, onDismiss = { shownLicense = null })
    }
}

@Composable
private fun Header(onLicenseClick: () -> Unit) {
    val context = LocalContext.current
    val icon = remember { LauncherIcon.resolve(context) }
    SettingsGroup(null) {
        row {
            ContentRow(it, null) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(HEADER_ICON_SIZE.dp), contentAlignment = Alignment.Center) {
                        // the glyph fills about half of the 108dp icon canvas
                        Image(painterResource(icon.foreground), contentDescription = null, modifier = Modifier.requiredSize((HEADER_ICON_SIZE * 2).dp))
                    }
                    Text(
                        stringResource(R.string.brand_name),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    listOf(R.string.brand_tagline, R.string.brand_copyright).map { stringResource(it) }.filter { it.isNotBlank() }.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(12.dp))

                    val build = if (BuildConfig.DEBUG) "${BuildConfig.VERSION_CODE} · ${BuildConfig.BUILD_TYPE}" else BuildConfig.VERSION_CODE.toString()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ShieldBadge(stringResource(R.string.about_badge_version), Shields.formatVersion(BuildConfig.VERSION_NAME), Shields.versionColor(BuildConfig.VERSION_NAME))
                        ShieldBadge(stringResource(R.string.about_badge_build), build, Shields.LIGHT_GREY)
                        ShieldBadge(stringResource(R.string.about_badge_license), APP_LICENSE.spdxId, Shields.licenseColor(APP_LICENSE.isCopyleft), onLicenseClick)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    position: RowPosition,
    source: AboutSource,
    versions: Map<String, String>,
    activity: BaseActivity,
    showLicense: (SourceLicense, AboutSource?) -> Unit,
) {
    GroupRowSurface(position, modifier = Modifier.clickable { activity.openWebPage(source.url) }) {
        Column(Modifier.weight(1f)) {
            Text(source.name, style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(source.descriptionRes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ShieldBadge(stringResource(R.string.about_badge_license), source.license.spdxId, Shields.licenseColor(source.license.isCopyleft)) {
                    showLicense(source.license, source)
                }
                (source.version ?: source.module?.let { versions[it] })?.let { version ->
                    ShieldBadge(stringResource(R.string.about_badge_version), Shields.formatVersion(version), Shields.versionColor(version))
                }
            }
        }
    }
}

// group:name → version of every library in the APK, written by the build (see app/build.gradle.kts)
private fun libraryVersions(context: Context): Map<String, String> = runCatching {
    context.assets.open(LIBRARY_VERSIONS_ASSET).bufferedReader().useLines { lines ->
        lines.mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }
}.getOrDefault(emptyMap())

/** The bundled text of [license], with [copyright] above it for licenses that ask for it. */
private fun licenseText(context: Context, license: SourceLicense, copyright: String?): String {
    val text = context.assets.open("licenses/${license.assetName}").bufferedReader().use { it.readText() }
    // the license files are wrapped at ~76 columns, which breaks twice on a phone; let each paragraph flow instead
    val reflowed = text.trim().split(PARAGRAPH_BREAK).joinToString("\n\n") { paragraph -> paragraph.lines().joinToString(" ") { it.trim() } }
    return if (copyright == null) reflowed else "$copyright\n\n$reflowed"
}

private const val LIBRARY_VERSIONS_ASSET = "about/library_versions.txt"
private const val HEADER_ICON_SIZE = 96
private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
private val APP_LICENSE = SourceLicense.GPL_3

// projects whose work this app builds on
private val SOURCES = listOf(
    AboutSource(
        name = "Cryptomator",
        descriptionRes = R.string.about_source_cryptomator,
        license = SourceLicense.GPL_3,
        url = "https://github.com/cryptomator/cryptomator",
    ),
)

// libraries packed into the app, versions come from the build

private val LIBRARIES = listOf(
    AboutSource(
        name = "needanapp design",
        descriptionRes = R.string.about_library_needanapp_design,
        license = SourceLicense.GPL_3,
        url = "https://needanapp.de",
    ),
    AboutSource(
        name = "Jetpack Compose",
        descriptionRes = R.string.about_library_compose,
        license = SourceLicense.APACHE_2,
        url = "https://developer.android.com/compose",
        module = "androidx.compose.material3:material3",
    ),
    AboutSource(
        name = "Haze",
        descriptionRes = R.string.about_library_haze,
        license = SourceLicense.APACHE_2,
        url = "https://github.com/chrisbanes/haze",
        module = "dev.chrisbanes.haze:haze",
    ),
    AboutSource(
        name = "MaterialKolor",
        descriptionRes = R.string.about_library_materialkolor,
        license = SourceLicense.MIT,
        url = "https://github.com/jordond/MaterialKolor",
        module = "com.materialkolor:material-kolor",
        copyright = "Copyright (c) 2025 Jordon de Hoog",
    ),
    AboutSource(
        name = "sshj",
        descriptionRes = R.string.about_library_sshj,
        license = SourceLicense.APACHE_2,
        url = "https://github.com/hierynomus/sshj",
        module = "com.hierynomus:sshj",
    ),
    AboutSource(
        name = "Bouncy Castle",
        descriptionRes = R.string.about_library_bouncycastle,
        license = SourceLicense.MIT,
        url = "https://www.bouncycastle.org",
        module = "org.bouncycastle:bcprov-jdk18on",
        copyright = "Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)",
    ),
    AboutSource(
        name = "SMBJ",
        descriptionRes = R.string.about_library_smbj,
        license = SourceLicense.APACHE_2,
        url = "https://github.com/hierynomus/smbj",
        module = "com.hierynomus:smbj",
    ),
    AboutSource(
        name = "SMB DCE/RPC",
        descriptionRes = R.string.about_library_dcerpc,
        license = SourceLicense.BSD,
        url = "https://github.com/rapid7/smbj-rpc",
        module = "com.rapid7.client:dcerpc",
        licenseUrl = "https://github.com/rapid7/smbj-rpc/blob/master/LICENSE",
    ),
    AboutSource(
        name = "OkHttp",
        descriptionRes = R.string.about_library_okhttp,
        license = SourceLicense.APACHE_2,
        url = "https://square.github.io/okhttp/",
        module = "com.squareup.okhttp3:okhttp",
    ),
    AboutSource(
        name = "Apache Commons Net",
        descriptionRes = R.string.about_library_commons_net,
        license = SourceLicense.APACHE_2,
        url = "https://commons.apache.org/proper/commons-net/",
        module = "commons-net:commons-net",
    ),
    AboutSource(
        name = "Zip4j",
        descriptionRes = R.string.about_library_zip4j,
        license = SourceLicense.APACHE_2,
        url = "https://github.com/srikanth-lingala/zip4j",
        module = "net.lingala.zip4j:zip4j",
    ),
    AboutSource(
        name = "7-Zip-JBinding",
        descriptionRes = R.string.about_library_sevenzip,
        license = SourceLicense.LGPL_2_1,
        url = "https://github.com/omicronapps/7-Zip-JBinding-4Android",
        // built from source in libs/7-zip-jbinding, with needafile's fix for archive settings
        version = "16.02-2.03+needafile",
        copyright = "7-Zip-JBinding: Copyright (C) Boris Brodski. 7-Zip: Copyright (C) Igor Pavlov. " +
            "The RAR decompression code of 7-Zip is subject to the unRAR license restriction.",
    ),
    AboutSource(
        name = "XZ for Java",
        descriptionRes = R.string.about_library_xz,
        license = SourceLicense.ZERO_BSD,
        url = "https://tukaani.org/xz/java.html",
        module = "org.tukaani:xz",
    ),
    AboutSource(
        name = "Cryptomator Cryptolib",
        descriptionRes = R.string.about_library_cryptolib,
        license = SourceLicense.AGPL_3,
        url = "https://github.com/cryptomator/cryptolib",
        module = "org.cryptomator:cryptolib",
    ),
    AboutSource(
        name = "SIV mode",
        descriptionRes = R.string.about_library_siv_mode,
        license = SourceLicense.MIT,
        url = "https://github.com/cryptomator/siv-mode",
        module = "org.cryptomator:siv-mode",
        copyright = "Copyright (c) 2015-2017 Cryptomator",
    ),
    AboutSource(
        name = "Gson",
        descriptionRes = R.string.about_library_gson,
        license = SourceLicense.APACHE_2,
        url = "https://github.com/google/gson",
        module = "com.google.code.gson:gson",
    ),
    AboutSource(
        name = "Guava",
        descriptionRes = R.string.about_library_guava,
        license = SourceLicense.APACHE_2,
        url = "https://github.com/google/guava",
        module = "com.google.guava:guava",
    ),
    AboutSource(
        name = "Coil",
        descriptionRes = R.string.about_library_coil,
        license = SourceLicense.APACHE_2,
        url = "https://github.com/coil-kt/coil",
        module = "io.coil-kt.coil3:coil-compose",
    ),
    AboutSource(
        name = "Telephoto",
        descriptionRes = R.string.about_library_telephoto,
        license = SourceLicense.APACHE_2,
        url = "https://github.com/saket/telephoto",
        module = "me.saket.telephoto:zoomable-image-coil3",
    ),
    AboutSource(
        name = "AndroidSVG",
        descriptionRes = R.string.about_library_androidsvg,
        license = SourceLicense.APACHE_2,
        url = "https://github.com/BigBadaboom/androidsvg",
        module = "com.caverock:androidsvg-aar",
    ),
    AboutSource(
        name = "JXL Coder",
        descriptionRes = R.string.about_library_jxl_coder,
        license = SourceLicense.APACHE_2,
        url = "https://github.com/awxkee/jxl-coder",
        module = "io.github.awxkee:jxl-coder-coil",
    ),
    AboutSource(
        name = "Android Image Cropper",
        descriptionRes = R.string.about_library_image_cropper,
        license = SourceLicense.APACHE_2,
        url = "https://github.com/CanHub/Android-Image-Cropper",
        module = "com.vanniktech:android-image-cropper",
    ),
    AboutSource(
        name = "android-pdfview",
        descriptionRes = R.string.about_library_pdfview,
        license = SourceLicense.APACHE_2,
        url = "https://github.com/Infomaniak/android-pdfview",
        module = "com.github.Infomaniak:android-pdfview",
    ),
    AboutSource(
        name = "PdfiumAndroid",
        descriptionRes = R.string.about_library_pdfium,
        license = SourceLicense.APACHE_2,
        url = "https://github.com/Infomaniak/PdfiumAndroid",
        module = "com.github.infomaniak:PdfiumAndroid",
    ),
    AboutSource(
        name = "commonmark-java",
        descriptionRes = R.string.about_library_commonmark,
        license = SourceLicense.BSD,
        url = "https://github.com/commonmark/commonmark-java",
        module = "org.commonmark:commonmark",
        licenseUrl = "https://github.com/commonmark/commonmark-java/blob/main/LICENSE.txt",
    ),
    AboutSource(
        name = "jsoup",
        descriptionRes = R.string.about_library_jsoup,
        license = SourceLicense.MIT,
        url = "https://jsoup.org",
        module = "org.jsoup:jsoup",
        copyright = "Copyright (c) 2009-2025 Jonathan Hedley <https://jsoup.org/>",
    ),
    AboutSource(
        name = "Apache Commons CSV",
        descriptionRes = R.string.about_library_commons_csv,
        license = SourceLicense.APACHE_2,
        url = "https://commons.apache.org/proper/commons-csv/",
        module = "org.apache.commons:commons-csv",
    ),
    AboutSource(
        name = "Material Components",
        descriptionRes = R.string.about_library_material,
        license = SourceLicense.APACHE_2,
        url = "https://github.com/material-components/material-components-android",
        module = "com.google.android.material:material",
    ),
    AboutSource(
        name = "Kotlin",
        descriptionRes = R.string.about_library_kotlin,
        license = SourceLicense.APACHE_2,
        url = "https://kotlinlang.org",
        module = "org.jetbrains.kotlin:kotlin-stdlib",
    ),
    AboutSource(
        name = "SLF4J",
        descriptionRes = R.string.about_library_slf4j,
        license = SourceLicense.MIT,
        url = "https://www.slf4j.org",
        module = "org.slf4j:slf4j-nop",
    ),
)
