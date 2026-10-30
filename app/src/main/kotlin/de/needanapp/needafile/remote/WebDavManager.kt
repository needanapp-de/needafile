package de.needanapp.needafile.remote

import de.needanapp.needafile.R
import de.needanapp.needafile.errors.LocalizedException
import de.needanapp.needafile.security.KnownCertificates
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Minimal WebDAV (RFC 4918) client on OkHttp for a single [RemoteServer] (see [RemoteFileSystem]).
 * Server paths are relative to [RemoteServer.url]. HTTP needs no kept-open session, so browsing and
 * transfers share one client and its connection pool. Every call blocks, so callers must run them
 * off the main thread and call [close] when done.
 *
 * HTTPS certificates Android doesn't trust (self-signed ones) are only accepted once the user confirmed them, see
 * [KnownCertificates]; until then calls fail with [UntrustedCertificateException].
 */
class WebDavManager(private val server: RemoteServer, private val knownCertificates: KnownCertificates) : ServerFileSystem {
    private val lazyClient = lazy {
        val trust = knownCertificates.Trust(baseUrl.host, baseUrl.port)
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .followRedirects(false) // OkHttp would turn a redirected PUT, MKCOL or MOVE into a GET
            .sslSocketFactory(trust.socketFactory, trust.trustManager)
            .hostnameVerifier(trust.hostnameVerifier)
            .addInterceptor { chain ->
                val request = chain.request()
                val authorized = if (server.username.isEmpty()) {
                    request
                } else {
                    // sent up front, so uploads don't have to be repeated after a 401 challenge
                    request.newBuilder().header("Authorization", Credentials.basic(server.username, server.password, Charsets.UTF_8)).build()
                }
                try {
                    chain.proceed(authorized)
                } catch (e: IOException) {
                    throw trust.rejectionOf(e)?.let { UntrustedCertificateException(it, e) } ?: e
                }
            }
            .build()
    }
    private val client by lazyClient

    private val baseUrl: HttpUrl by lazy {
        val url = server.url.trim().let { if ("://" in it) it else "https://$it" }
        (if (url.endsWith("/")) url else "$url/").toHttpUrlOrNull() ?: throw LocalizedException(R.string.remote_error_invalid_webdav_url, server.url)
    }

    /** Closes idle connections; the manager stays usable. */
    override fun close() {
        if (lazyClient.isInitialized()) {
            client.connectionPool.evictAll()
        }
    }

    /** Verifies URL/credentials with a PROPFIND on the base URL; throws on failure. */
    override fun testConnection() {
        propfind(baseUrl, depth = 0)
    }

    override fun listFiles(path: String): List<BrowserFile> {
        val folderUrl = urlFor(path, isFolder = true)
        val entries = propfind(folderUrl, depth = 1)

        // the folder itself is part of the answer, usually first; hrefs may be absolute URLs or paths
        val folderSegments = folderUrl.pathSegments.filter { it.isNotEmpty() }
        val selfIndex = entries.indexOfFirst { it.segments == folderSegments }.takeIf { it >= 0 } ?: 0
        return entries.filterIndexed { index, entry -> index != selfIndex && entry.segments.isNotEmpty() }
            .map { entry ->
                val name = entry.segments.last()
                BrowserFile(
                    name = name,
                    path = joinRemotePath(path, name),
                    isDirectory = entry.isCollection,
                    size = if (entry.isCollection) 0 else entry.size,
                    lastModified = entry.lastModified,
                )
            }
            .sortedWith(BrowserFile.DEFAULT_ORDER)
    }

    override fun openTransfer(): TransferConnection = WebDavTransfer()

    /**
     * Copies over the shared client. Downloads continue with a range request; uploads can't continue (plain WebDAV
     * has no partial PUT), a broken one is sent anew.
     */
    private inner class WebDavTransfer : TransferConnection {
        override fun list(path: String) = listFiles(path)

        override fun sizeOf(path: String): Long? {
            val entry = try {
                propfind(urlFor(path), depth = 0).firstOrNull()
            } catch (e: HttpStatusException) {
                if (e.code == 404 || e.code == 410) return null
                throw e
            }
            return entry?.takeIf { !it.isCollection }?.size
        }

        override fun makeFolder(path: String) = mkcol(path, allowExisting = true)

        override fun read(path: String, offset: Long, block: (InputStream) -> Unit) {
            val request = Request.Builder().url(urlFor(path)).apply { if (offset > 0) header("Range", "bytes=$offset-") }.build()
            client.newCall(request).execute().use { response ->
                // nothing left after the offset: the file was complete already
                if (offset > 0 && response.code == 416) {
                    block(ByteArrayInputStream(ByteArray(0)))
                    return
                }
                if (!response.isSuccessful) throw httpError(response)
                val isPartial = response.code == 206
                if (isPartial && response.header("Content-Range")?.startsWith("bytes $offset-") != true) {
                    throw HttpStatusException(response.code, R.string.remote_error_http, response.code, "Content-Range")
                }
                response.body.byteStream().use { input ->
                    // a server ignoring the range sends the whole file, then what's there already is skipped
                    if (offset > 0 && !isPartial) skipFully(input, offset)
                    block(input)
                }
            }
        }

        override fun write(path: String, offset: Long, length: Long, modified: Long, block: (OutputStream) -> Unit) {
            val body = object : RequestBody() {
                override fun contentType() = OCTET_STREAM
                override fun contentLength() = length

                // never let OkHttp replay a partly sent (or cancelled) upload on its own
                override fun isOneShot() = true

                override fun writeTo(sink: BufferedSink) = block(sink.outputStream())
            }
            val request = Request.Builder()
                .url(urlFor(path))
                .put(body)
                .header("X-OC-Mtime", (modified / 1000).toString()) // keeps the time on Nextcloud/ownCloud
                .build()
            execute(request) {}
        }

        override val canContinueWrite = false

        override fun deleteFile(path: String) = deleteResource(path)

        override fun close() = Unit
    }

    private fun skipFully(input: InputStream, count: Long) {
        var left = count
        val buffer = ByteArray(SKIP_BUFFER_SIZE)
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (read < 0) throw EOFException()
            left -= read
        }
    }

    /** Deletes the given files and folders on the server, folders with all their content. */
    override fun delete(paths: List<String>) {
        paths.forEach { path ->
            if (path.trim('/').isEmpty()) throw LocalizedException(R.string.remote_error_root_delete)
            deleteResource(path)
        }
    }

    /** A 207 answer lists members that could not be deleted, so it counts as failure; already gone is fine. */
    private fun deleteResource(path: String) {
        val request = Request.Builder().url(urlFor(path)).delete().build()
        execute(request, isOk = { it == 200 || it == 202 || it == 204 || it == 404 }) {}
    }

    override fun move(path: String, newPath: String) {
        val request = Request.Builder()
            .url(urlFor(path))
            .method("MOVE", null)
            .header("Destination", urlFor(newPath).toString())
            .header("Overwrite", "F")
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 412) throw LocalizedException(R.string.remote_error_name_exists, newPath.substringAfterLast('/'))
            if (!response.isSuccessful) throw httpError(response)
        }
    }

    override fun createFolder(parentPath: String, name: String): String {
        val path = joinRemotePath(parentPath, name)
        mkcol(path, allowExisting = false)
        return path
    }

    /** A 405 answer to MKCOL means the folder exists already. */
    private fun mkcol(path: String, allowExisting: Boolean) {
        val request = Request.Builder().url(urlFor(path, isFolder = true)).method("MKCOL", null).build()
        execute(request, isOk = { it in 200..299 || (allowExisting && it == 405) }) {}
    }

    private fun propfind(url: HttpUrl, depth: Int): List<DavEntry> {
        val request = Request.Builder()
            .url(url)
            .header("Depth", depth.toString())
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .build()
        return execute(request) { response -> parseMultiStatus(response) }
    }

    private class DavEntry(val segments: List<String>, val isCollection: Boolean, val size: Long, val lastModified: Long)

    /** Only properties of "200 OK" propstats count, the others just name what the server doesn't know. */
    private fun parseMultiStatus(response: Response): List<DavEntry> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            try {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (ignored: Exception) {
                // Android's parser doesn't know the feature, but doesn't load external entities either
            }
        }
        val document = response.body.byteStream().use { factory.newDocumentBuilder().parse(it) }
        val responses = document.getElementsByTagNameNS(DAV_NAMESPACE, "response")
        return (0 until responses.length).mapNotNull { index ->
            val element = responses.item(index) as Element
            val href = element.child("href")?.textContent?.trim() ?: return@mapNotNull null
            val url = response.request.url.resolve(href) ?: return@mapNotNull null
            val props = element.children("propstat")
                .filter { propstat -> propstat.child("status")?.let { it.textContent.trim().split(' ').getOrNull(1) == "200" } ?: true }
                .mapNotNull { it.child("prop") }
            fun prop(name: String) = props.firstNotNullOfOrNull { it.child(name) }

            DavEntry(
                segments = url.pathSegments.filter { it.isNotEmpty() },
                isCollection = prop("resourcetype")?.child("collection") != null,
                size = prop("getcontentlength")?.textContent?.trim()?.toLongOrNull() ?: 0,
                lastModified = parseHttpDate(prop("getlastmodified")?.textContent),
            )
        }
    }

    private fun Element.children(name: String): List<Element> {
        val nodes = childNodes
        return (0 until nodes.length).map { nodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.namespaceURI == DAV_NAMESPACE && it.localName == name }
    }

    private fun Element.child(name: String) = children(name).firstOrNull()

    private fun parseHttpDate(value: String?): Long {
        if (value.isNullOrBlank()) return 0
        return try {
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            0
        }
    }

    /** The URL of the server path [path], percent-encoded segment by segment; folders get a trailing slash. */
    private fun urlFor(path: String, isFolder: Boolean = false): HttpUrl {
        val builder = baseUrl.newBuilder()
        path.split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        if (isFolder) {
            builder.addPathSegment("")
        }
        return builder.build()
    }

    /** Runs [request] and hands the response to [block], throwing unless [isOk] accepts the status code. */
    private fun <T> execute(request: Request, isOk: (Int) -> Boolean = { it in 200..299 }, block: (Response) -> T): T =
        client.newCall(request).execute().use { response ->
            if (!isOk(response.code)) throw httpError(response)
            block(response)
        }

    private class HttpStatusException(val code: Int, messageRes: Int, vararg formatArgs: Any) : LocalizedException(messageRes, *formatArgs), ServerErrorReply {
        override val isTemporary get() = code in TEMPORARY_STATUSES
    }

    private fun httpError(response: Response): IOException {
        val code = response.code
        return when (code) {
            401 -> HttpStatusException(code, R.string.remote_error_wrong_credentials)
            403 -> HttpStatusException(code, R.string.remote_error_access_denied)
            404, 410 -> HttpStatusException(code, R.string.remote_error_not_found)
            423 -> HttpStatusException(code, R.string.remote_error_locked)
            507 -> HttpStatusException(code, R.string.remote_error_disk_full)
            in 300..399 -> HttpStatusException(code, R.string.remote_error_redirect, response.header("Location").orEmpty())
            else -> HttpStatusException(code, R.string.remote_error_http, code, response.message.ifEmpty { REASON_PHRASES[code].orEmpty() })
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 20L
        private const val SKIP_BUFFER_SIZE = 64 * 1024

        // timeouts, overload and gateways that lost the server for a moment
        private val TEMPORARY_STATUSES = setOf(408, 429, 502, 503, 504)
        private const val DAV_NAMESPACE = "DAV:"
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""

        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        // HTTP/2 answers carry no reason phrase
        private val REASON_PHRASES = mapOf(
            400 to "Bad Request",
            401 to "Unauthorized",
            403 to "Forbidden",
            404 to "Not Found",
            405 to "Method Not Allowed",
            409 to "Conflict",
            412 to "Precondition Failed",
            413 to "Content Too Large",
            415 to "Unsupported Media Type",
            423 to "Locked",
            500 to "Internal Server Error",
            502 to "Bad Gateway",
            503 to "Service Unavailable",
            507 to "Insufficient Storage",
        )
    }
}
