package de.needanapp.needafile.viewer.image

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.exifinterface.media.ExifInterface
import de.needanapp.design.components.CardTileGroups
import de.needanapp.design.components.Detail
import de.needanapp.design.components.DetailTile
import de.needanapp.design.components.detailLines
import de.needanapp.needafile.R
import de.needanapp.needafile.core.formatDate
import de.needanapp.needafile.core.formatSize
import de.needanapp.needafile.core.getMimeType
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * What the camera wrote into a picture (EXIF): with what and when it was [taken], how it was [exposed] and where
 * ([place]); and what is known of its [file]. For [ImageDetails].
 */
internal class ImageDetailLists(val taken: List<Detail>, val exposed: List<Detail>, val place: ImagePlace?, val file: List<Detail>)

/** Where a picture was taken: the [detail] to show and the spot to open on a map. */
internal class ImagePlace(val detail: Detail, val latitude: Double, val longitude: Double)

/** Reads [ImageDetailLists] of [image]; [path] is where it lies, null where that is not to be shown. Blocks. */
internal fun imageDetails(context: Context, image: ViewerImage, path: String?): ImageDetailLists {
    val exif = context.readExif(image.uri)
    return ImageDetailLists(
        taken = exif?.let { takenDetails(context, it) }.orEmpty(),
        exposed = exif?.let { exposureDetails(context, it) }.orEmpty(),
        place = exif?.latLong?.let { (latitude, longitude) ->
            ImagePlace(Detail(context.getString(R.string.gps_coordinates), "%.6f, %.6f".format(Locale.ROOT, latitude, longitude)), latitude, longitude)
        },
        file = fileDetails(context, image, path),
    )
}

/**
 * [details] as tiles unfolding in the viewer's card below its actions: when, with what, how exposed and where the
 * picture was taken, then its file. Name, size, date and resolution are on the card already. A tap on the place runs
 * [onShowOnMap].
 */
@Composable
internal fun ImageDetails(details: ImageDetailLists, onShowOnMap: (ImagePlace) -> Unit) {
    val showOnMap = stringResource(R.string.show_on_map)
    CardTileGroups {
        if (details.taken.isNotEmpty() || details.exposed.isNotEmpty() || details.place != null) {
            label(stringResource(R.string.image_info_capture))
            // camera and time side by side, even when long
            line(details.taken.map { detail -> { modifier, shape -> DetailTile(detail, shape, modifier) } })
            detailLines(details.exposed)
            details.place?.let { place ->
                line({ modifier, shape ->
                    DetailTile(place.detail, shape, modifier, onClick = { onShowOnMap(place) }, actionIcon = Icons.Outlined.Map, actionLabel = showOnMap)
                })
            }
        }
        if (details.file.isNotEmpty()) {
            label(stringResource(R.string.file))
            detailLines(details.file)
        }
    }
}

/** What the viewer's card says below the name: size, the date [modified] and resolution, as far as known. Blocks. */
internal fun imageFacts(context: Context, image: ViewerImage, modified: Long?): String? {
    val size = image.path?.let { File(it).length() } ?: runCatching {
        context.contentResolver.openAssetFileDescriptor(image.uri, "r")?.use { it.length }
    }.getOrNull()
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching { context.contentResolver.openInputStream(image.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
    val facts = listOfNotNull(
        size?.takeIf { it >= 0 }?.formatSize(),
        modified?.formatDate(context),
        if (bounds.outWidth > 0 && bounds.outHeight > 0) context.getString(R.string.resolution_value, bounds.outWidth, bounds.outHeight) else null,
    )
    // one after the other, separated as size and date are elsewhere
    return facts.reduceOrNull { joined, next -> context.getString(R.string.size_and_date, joined, next) }
}

private fun fileDetails(context: Context, image: ViewerImage, path: String?): List<Detail> = buildList {
    val type = image.path?.getMimeType()?.takeIf { it.isNotEmpty() } ?: context.contentResolver.getType(image.uri)
    type?.let { add(Detail(context.getString(R.string.file_type), it)) }
    path?.let { add(Detail(context.getString(R.string.path), it)) }
}

private fun takenDetails(context: Context, exif: ExifInterface): List<Detail> = buildList {
    val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
    val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
    // many cameras repeat the maker in the model name
    val cameraName = if (model.startsWith(make, ignoreCase = true)) model else "$make $model".trim()
    if (cameraName.isNotEmpty()) add(Detail(context.getString(R.string.camera), cameraName))
    takenAt(exif)?.let {
        add(Detail(context.getString(R.string.date_taken), it.formatDate(context)))
    }
}

private fun exposureDetails(context: Context, exif: ExifInterface): List<Detail> = buildList {
    exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let {
        add(Detail(context.getString(R.string.f_number), context.getString(R.string.viewer_f_number_value, decimal(it))))
    }
    exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let {
        add(Detail(context.getString(R.string.exposure_time), context.getString(R.string.viewer_exposure_value, exposure(it))))
    }
    exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }?.let {
        add(Detail(context.getString(R.string.viewer_iso), it.toString()))
    }
    exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let {
        add(Detail(context.getString(R.string.focal_length), context.getString(R.string.viewer_focal_length_value, decimal(it))))
    }
}

private fun takenAt(exif: ExifInterface): Long? {
    val text = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME) ?: return null
    return runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).parse(text)?.time }.getOrNull()
}

/** 1.8 or 4 (no ".0"), in the user's number format. */
private fun decimal(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(value)

/** Short exposures as the usual fraction (1/250), long ones in seconds (2.5). */
private fun exposure(seconds: Double) = if (seconds < 1) "1/${Math.round(1 / seconds)}" else decimal(seconds)
