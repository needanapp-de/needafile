package de.needanapp.needafile.discovery

import android.net.Network
import android.os.SystemClock
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.concurrent.thread

/**
 * WS-Discovery, the way Windows finds computers in its network view: a probe for devices goes to the multicast group
 * 239.255.255.250:3702, Windows computers and NAS running wsdd answer. Their address follows from the answer (or a
 * resolve request), their name from a WS-Transfer "Get" to the device's web service. Only computers are reported,
 * printers and other devices answering as well are left out. [onFound] gets the name and IPv4 address, on a
 * background thread.
 */
internal class WsDiscovery(private val network: Network?, private val onFound: (name: String, address: String) -> Unit) {
    @Volatile
    private var isRunning = false

    @Volatile
    private var socket: DatagramSocket? = null
    private val nameLookups = Executors.newFixedThreadPool(NAME_LOOKUP_THREADS)
    private val endpoints = HashSet<String>()

    fun start() {
        isRunning = true
        thread(name = "ws-discovery", isDaemon = true) { listen() }
    }

    fun stop() {
        isRunning = false
        socket?.close()
        nameLookups.shutdownNow()
    }

    private fun listen() {
        val socket = try {
            DatagramSocket().apply {
                network?.bindSocket(this)
                soTimeout = RECEIVE_TIMEOUT_MS
            }
        } catch (e: IOException) {
            return
        }
        this.socket = socket
        val group = InetSocketAddress(MULTICAST_ADDRESS, PORT)
        val buffer = ByteArray(MAX_MESSAGE_SIZE)
        try {
            val end = SystemClock.elapsedRealtime() + LISTEN_MS
            var probesSent = 0
            var nextProbe = 0L
            while (isRunning && SystemClock.elapsedRealtime() < end) {
                // UDP may lose a probe, so it goes out more than once
                if (probesSent < PROBE_COUNT && SystemClock.elapsedRealtime() >= nextProbe) {
                    send(socket, probeMessage(), group)
                    probesSent++
                    nextProbe = SystemClock.elapsedRealtime() + PROBE_INTERVAL_MS
                }
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                runCatching { handleAnswer(socket, group, packet) }
            }
        } catch (ignored: IOException) {
            // stopped, or the network went away
        } finally {
            socket.close()
        }
    }

    private fun send(socket: DatagramSocket, message: String, target: InetSocketAddress) {
        val bytes = message.toByteArray()
        socket.send(DatagramPacket(bytes, bytes.size, target))
    }

    /** A ProbeMatch or ResolveMatch: computers with an address get their name looked up, others are resolved first. */
    private fun handleAnswer(socket: DatagramSocket, group: InetSocketAddress, packet: DatagramPacket) {
        val document = parse(packet.data.copyOf(packet.length)) ?: return
        val matches = document.elementsNamed("ProbeMatch") + document.elementsNamed("ResolveMatch")
        matches.forEach { match ->
            val endpoint = match.firstNamed("Address")?.textContent?.trim().orEmpty()
            val types = match.firstNamed("Types")?.textContent.orEmpty()
            // "pub:Computer" whatever the prefix; printers and the like answer probes too
            if (endpoint.isEmpty() || types.split(' ').none { it.substringAfter(':') == "Computer" }) return@forEach

            val xaddr = match.firstNamed("XAddrs")?.textContent?.trim()?.split(' ')
                ?.firstOrNull { it.startsWith("http://") && runCatching { IPV4.matches(URL(it).host) }.getOrDefault(false) }
            if (xaddr == null) {
                send(socket, resolveMessage(endpoint), group)
                return@forEach
            }
            val isNew = synchronized(endpoints) { endpoints.add(endpoint) }
            if (isNew) {
                val address = URL(xaddr).host
                nameLookups.execute { onFound(lookUpName(endpoint, xaddr) ?: address, address) }
            }
        }
    }

    /** The computer's name from its metadata: "NAME/Workgroup:…" of pub:Computer, else the device's friendly name. */
    private fun lookUpName(endpoint: String, xaddr: String): String? = try {
        val url = URL(xaddr)
        val connection = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        try {
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/soap+xml")
            connection.outputStream.use { it.write(getMessage(endpoint).toByteArray()) }
            val bytes = connection.inputStream.use { it.readNBytesCompat(MAX_MESSAGE_SIZE) }
            val document = parse(bytes)
            val computer = document?.firstNamed("Computer")?.textContent?.substringBefore('/')?.trim()
            val friendlyName = document?.firstNamed("FriendlyName")?.textContent?.trim()
            (computer?.takeIf { it.isNotEmpty() } ?: friendlyName?.takeIf { it.isNotEmpty() })?.take(MAX_NAME_LENGTH)
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        null
    }

    private fun parse(bytes: ByteArray): Document? = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            try {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            } catch (ignored: Exception) {
                // Android's parser doesn't know the feature, but doesn't load external entities either
            }
        }
        factory.newDocumentBuilder().parse(bytes.inputStream())
    } catch (e: Exception) {
        null
    }

    private fun Document.elementsNamed(localName: String): List<Element> {
        val nodes = getElementsByTagNameNS("*", localName)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Document.firstNamed(localName: String) = elementsNamed(localName).firstOrNull()

    private fun Element.firstNamed(localName: String) = getElementsByTagNameNS("*", localName).item(0) as? Element

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        while (output.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun newMessageId() = "urn:uuid:${UUID.randomUUID()}"

    private fun probeMessage() = envelope(
        to = DISCOVERY_TO,
        action = "$DISCOVERY_NS/Probe",
        body = "<wsd:Probe><wsd:Types>wsdp:Device</wsd:Types></wsd:Probe>",
    )

    private fun resolveMessage(endpoint: String) = envelope(
        to = DISCOVERY_TO,
        action = "$DISCOVERY_NS/Resolve",
        body = "<wsd:Resolve><wsa:EndpointReference><wsa:Address>${escape(endpoint)}</wsa:Address></wsa:EndpointReference></wsd:Resolve>",
    )

    private fun getMessage(endpoint: String) = envelope(
        to = escape(endpoint),
        action = "http://schemas.xmlsoap.org/ws/2004/09/transfer/Get",
        body = "",
        extraHeader = "<wsa:ReplyTo><wsa:Address>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</wsa:Address></wsa:ReplyTo>",
    )

    private fun envelope(to: String, action: String, body: String, extraHeader: String = "") =
        """<?xml version="1.0" encoding="utf-8"?>""" +
            """<soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" """ +
            """xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:wsd="$DISCOVERY_NS" """ +
            """xmlns:wsdp="http://schemas.xmlsoap.org/ws/2006/02/devprof">""" +
            "<soap:Header><wsa:To>$to</wsa:To><wsa:Action>$action</wsa:Action><wsa:MessageID>${newMessageId()}</wsa:MessageID>$extraHeader</soap:Header>" +
            "<soap:Body>$body</soap:Body></soap:Envelope>"

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        private const val MULTICAST_ADDRESS = "239.255.255.250"
        private const val PORT = 3702
        private const val DISCOVERY_NS = "http://schemas.xmlsoap.org/ws/2005/04/discovery"
        private const val DISCOVERY_TO = "urn:schemas-xmlsoap-org:ws:2005:04:discovery"
        private const val PROBE_COUNT = 3
        private const val PROBE_INTERVAL_MS = 1500L
        private const val LISTEN_MS = 8000L
        private const val RECEIVE_TIMEOUT_MS = 500
        private const val HTTP_TIMEOUT_MS = 3000
        private const val MAX_MESSAGE_SIZE = 64 * 1024
        private const val BUFFER_SIZE = 8 * 1024
        private const val MAX_NAME_LENGTH = 64
        private const val NAME_LOOKUP_THREADS = 2
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }
}
