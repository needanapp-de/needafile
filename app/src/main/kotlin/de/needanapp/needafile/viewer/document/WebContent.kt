package de.needanapp.needafile.viewer.document

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import de.needanapp.needafile.R
import de.needanapp.needafile.core.toast
import android.graphics.Color as AndroidColor

/** Finding text in a [WebContent]: the matches' count and which one is selected. */
internal class WebSearch {
    var view: WebView? = null

    fun find(query: String) {
        if (query.isEmpty()) view?.clearMatches() else view?.findAllAsync(query)
    }

    fun next(forward: Boolean) = view?.findNext(forward)
}

/**
 * A document as a web page (DOCX, ODT, HTML and Markdown, cleaned by [HtmlCleaner]): in the app's colors,
 * without scripts or network, links open in the browser. [top] and [bottom] keep the text clear of header and
 * navigation bar while it scrolls below them.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebContent(html: String, top: Dp, bottom: Dp, search: WebSearch, onScroll: (Float) -> Unit, onFindResult: (active: Int, count: Int) -> Unit) {
    val context = LocalContext.current
    val currentOnScroll by rememberUpdatedState(onScroll)
    val colors = MaterialTheme.colorScheme
    val page = remember(html, colors, top, bottom) {
        buildPage(html, colors.background, colors.onBackground, colors.primary, colors.outlineVariant, colors.surfaceContainerHigh, top, bottom)
    }
    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.blockNetworkImage = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url
                    // jumps inside the document stay here
                    if (target.host == BASE_HOST && target.fragment != null) return false
                    if (HtmlCleaner.isExternalLink(target.toString())) {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, target)) }
                            .onFailure { context.toast(R.string.no_app_found) }
                    }
                    return true
                }
            }
            setFindListener { active, count, _ -> onFindResult(active, count) }
            // negative while reading on toward the end
            setOnScrollChangeListener { _, _, scrollY, _, oldScrollY -> currentOnScroll((oldScrollY - scrollY).toFloat()) }
        }
    }
    LaunchedEffect(page) { webView.loadDataWithBaseURL(BASE_URL, page, "text/html", "UTF-8", null) }
    DisposableEffect(webView) {
        search.view = webView
        onDispose {
            search.view = null
            webView.stopLoading()
            webView.destroy()
        }
    }
    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
}

private fun buildPage(html: String, background: Color, text: Color, accent: Color, outline: Color, code: Color, top: Dp, bottom: Dp): String {
    val style = """
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
            :root { color-scheme: light dark; }
            html { background: ${background.css()}; }
            body {
                margin: 0;
                padding: ${top.value.toInt() + 8}px 20px ${bottom.value.toInt() + 24}px;
                color: ${text.css()};
                font-family: sans-serif;
                line-height: 1.55;
                overflow-wrap: break-word;
            }
            a { color: ${accent.css()}; }
            img { max-width: 100%; height: auto; }
            h1, h2, h3, h4 { line-height: 1.25; }
            table { border-collapse: collapse; display: block; max-width: 100%; overflow-x: auto; margin: 12px 0; border: none; }
            th, td { border: 1px solid ${outline.css()}; padding: 6px 10px; vertical-align: top; }
            pre, code { background: ${code.css()}; border-radius: 6px; font-family: monospace; }
            pre { padding: 12px; overflow-x: auto; }
            code { padding: 1px 4px; }
            pre code { padding: 0; }
            blockquote { margin: 12px 0; padding-left: 12px; border-left: 4px solid ${accent.css()}; }
            hr { border: none; border-top: 1px solid ${outline.css()}; }
        </style>
    """.trimIndent()
    return if (html.contains("<head>")) html.replaceFirst("<head>", "<head>$style") else "<html><head>$style</head><body>$html</body></html>"
}

private fun Color.css(): String {
    val argb = toArgb()
    return "rgba(${(argb shr 16) and 0xFF}, ${(argb shr 8) and 0xFF}, ${argb and 0xFF}, ${alpha})"
}

private const val BASE_HOST = "document.needafile.invalid"
private const val BASE_URL = "https://$BASE_HOST/"
