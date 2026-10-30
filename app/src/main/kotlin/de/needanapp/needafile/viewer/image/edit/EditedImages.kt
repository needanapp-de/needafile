package de.needanapp.needafile.viewer.image.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import de.needanapp.needafile.core.openFileForWriting
import de.needanapp.needafile.core.rescanPaths
import de.needanapp.needafile.viewer.image.ViewerImage
import de.needanapp.needafile.viewer.image.ViewerImages
import de.needanapp.needafile.viewer.image.copyDetailsTo
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/** A picture ready for editing: its pixels (upright, at most [MAX_EDGE] on the long side) and its original size. */
internal class LoadedImage(val bitmap: Bitmap, val originalWidth: Int, val originalHeight: Int, val exif: ExifInterface?)

internal object EditedImages {
    /** Longest side of the picture while editing; larger ones are scaled down (memory: ~4096 × 3072 × 4 bytes). */
    const val MAX_EDGE = 4096
    private const val QUALITY = 90

    // formats that can be written back in their own format; everything else is saved as a JPEG copy
    private val writable = setOf("jpg", "jpeg", "png", "webp")

    fun canOverwrite(image: ViewerImage) = extension(image.name) in writable

    /** Decodes [image] upright and mutable; Android decodes most formats itself, Coil the rest (SVG, JPEG XL). */
    suspend fun load(context: Context, image: ViewerImage): LoadedImage {
        var width = 0
        var height = 0
        val bitmap = runCatching {
            val source = image.path?.let { ImageDecoder.createSource(File(it)) } ?: ImageDecoder.createSource(context.contentResolver, image.uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                width = info.size.width
                height = info.size.height
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                val scale = MAX_EDGE.toFloat() / max(width, height)
                if (scale < 1) decoder.setTargetSize((width * scale).roundToInt(), (height * scale).roundToInt())
            }
        }.getOrNull() ?: loadWithCoil(context, image).also {
            width = it.width
            height = it.height
        }
        val mutable = if (bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val exif = runCatching { context.contentResolver.openInputStream(image.uri)?.use { ExifInterface(it) } }.getOrNull()
        return LoadedImage(mutable, width, height, exif)
    }

    private suspend fun loadWithCoil(context: Context, image: ViewerImage): Bitmap {
        val request = ImageRequest.Builder(context).data(image.model).size(MAX_EDGE).allowHardware(false).build()
        val result = ViewerImages.loader(context).execute(request) as? SuccessResult ?: throw IOException(image.name)
        return result.image.toBitmap()
    }

    /** The name of a copy next to [name]: "photo-edited.jpg", "photo-edited-2.jpg" … in [folder]; JPEG for other formats. */
    fun copyName(folder: String, name: String, suffix: String): String {
        val base = name.substringBeforeLast('.')
        val extension = extension(name).takeIf { it in writable } ?: "jpg"
        var candidate = "$base-$suffix.$extension"
        var number = 2
        while (File(folder, candidate).exists()) candidate = "$base-$suffix-${number++}.$extension"
        return candidate
    }

    /** Writes [bitmap] to [path] in the format its extension names (JPEG otherwise), with the camera data of [exif]. */
    fun saveToPath(context: Context, bitmap: Bitmap, path: String, exif: ExifInterface?) {
        val format = formatFor(path)
        val output = openFileForWriting(path)
        output.use { if (!bitmap.compress(format, QUALITY, it)) throw IOException(path) }
        // orientation and size aren't copied: the pixels are upright now and may have another size
        exif?.let { runCatching { it.copyDetailsTo(ExifInterface(path)) } }
        context.rescanPaths(arrayListOf(path))
    }

    /** Replaces what another app handed over at [uri]; only for formats that can be written back. */
    fun saveToUri(context: Context, bitmap: Bitmap, uri: Uri, name: String, exif: ExifInterface?) {
        val format = formatFor(name)
        val output = runCatching { context.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: context.contentResolver.openOutputStream(uri, "w") ?: throw IOException(name)
        output.use { if (!bitmap.compress(format, QUALITY, it)) throw IOException(name) }
        exif?.let { source ->
            runCatching {
                context.contentResolver.openFileDescriptor(uri, "rw")?.use { source.copyDetailsTo(ExifInterface(it.fileDescriptor)) }
            }
        }
    }

    private fun formatFor(name: String) = when (extension(name)) {
        "png" -> Bitmap.CompressFormat.PNG
        "webp" -> Bitmap.CompressFormat.WEBP_LOSSY
        else -> Bitmap.CompressFormat.JPEG
    }

    private fun mimeFor(format: Bitmap.CompressFormat) = when (format) {
        Bitmap.CompressFormat.PNG -> "image/png"
        Bitmap.CompressFormat.WEBP_LOSSY, Bitmap.CompressFormat.WEBP_LOSSLESS -> "image/webp"
        else -> "image/jpeg"
    }

    private fun extension(name: String) = name.substringAfterLast('.', "").lowercase()
}
