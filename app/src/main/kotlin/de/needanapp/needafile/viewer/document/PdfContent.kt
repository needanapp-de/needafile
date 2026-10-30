package de.needanapp.needafile.viewer.document

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.infomaniak.lib.pdfview.PDFView
import com.infomaniak.lib.pdfview.util.FitPolicy
import com.shockwave.pdfium.PdfPasswordException
import de.needanapp.design.components.ContentDialog
import de.needanapp.design.components.SecretTextField
import de.needanapp.design.components.SignalCircularProgressIndicator
import de.needanapp.needafile.R
import java.io.File

/**
 * A PDF (rendered by Pdfium): pages below each other, pinch and double tap to zoom, a tap for full screen. Asks for
 * the password of protected files.
 */
@Composable
internal fun PdfContent(
    document: ViewerDocument,
    modifier: Modifier,
    onTap: () -> Unit,
    onPageChange: (page: Int, count: Int) -> Unit,
    onScroll: (Float) -> Unit,
    onError: () -> Unit,
    onCancelPassword: () -> Unit,
) {
    var view by remember { mutableStateOf<PDFView?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var password by remember { mutableStateOf<String?>(null) }
    var askPassword by remember { mutableStateOf(false) }
    var wrongPassword by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val background = MaterialTheme.colorScheme.surfaceContainer.toArgb()
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnPageChange by rememberUpdatedState(onPageChange)
    val currentOnScroll by rememberUpdatedState(onScroll)
    // where the pages stood at the last scroll step
    val lastOffset = remember { floatArrayOf(0f) }

    DisposableEffect(Unit) { onDispose { view?.recycle() } }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer)) {
        // a new attempt (e.g. with a password) builds a new view
        key(attempt) {
            AndroidView(
                factory = { context ->
                    PDFView(context, null).apply {
                        view = this
                        setBackgroundColor(background)
                        minZoom = 1f
                        midZoom = MID_ZOOM
                        maxZoom = MAX_ZOOM
                        useBestQuality(true)
                        val configurator = document.path?.let { fromFile(File(it)) } ?: fromUri(document.uri)
                        configurator
                            .password(password)
                            .defaultPage(page)
                            .enableSwipe(true)
                            .swipeHorizontal(false)
                            .enableDoubletap(true)
                            .enableAnnotationRendering(true)
                            .enableAntialiasing(true)
                            .pageFitPolicy(FitPolicy.WIDTH)
                            .autoSpacing(false)
                            .pageSnap(false)
                            .pageFling(false)
                            .pageSeparatorSpacing(PAGE_SPACING)
                            .onTap {
                                currentOnTap()
                                false
                            }
                            .onPageScroll { _, _ ->
                                currentOnScroll(currentYOffset - lastOffset[0])
                                lastOffset[0] = currentYOffset
                            }
                            .onPageChange { current, count ->
                                page = current
                                currentOnPageChange(current, count)
                            }
                            .onLoad { count ->
                                isLoading = false
                                lastOffset[0] = currentYOffset
                                currentOnPageChange(page, count)
                            }
                            .onError { error ->
                                isLoading = false
                                if (error is PdfPasswordException) {
                                    wrongPassword = password != null
                                    askPassword = true
                                } else {
                                    onError()
                                }
                            }
                            .load()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isLoading) SignalCircularProgressIndicator(Modifier.align(Alignment.Center))
    }

    if (askPassword) {
        var input by remember { mutableStateOf("") }
        fun open() {
            if (input.isEmpty()) return
            password = input
            askPassword = false
            isLoading = true
            attempt++
        }
        ContentDialog(
            title = stringResource(R.string.document_password_title),
            confirmLabel = stringResource(R.string.document_password_open),
            onConfirm = ::open,
            onDismiss = onCancelPassword,
            confirmEnabled = input.isNotEmpty(),
            icon = Icons.Outlined.Lock,
        ) {
            SecretTextField(
                value = input,
                onValueChange = { input = it },
                label = stringResource(R.string.password),
                error = stringResource(R.string.document_password_wrong).takeIf { wrongPassword },
                onDone = ::open,
            )
        }
    }
}

private const val MID_ZOOM = 1.75f
private const val MAX_ZOOM = 5f
private const val PAGE_SPACING = 8
