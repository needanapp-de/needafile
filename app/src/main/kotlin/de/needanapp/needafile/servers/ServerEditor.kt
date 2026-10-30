package de.needanapp.needafile.servers

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.NoEncryption
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.needanapp.design.components.ActionTile
import de.needanapp.design.components.BottomPillSpacer
import de.needanapp.design.components.CardStatus
import de.needanapp.design.components.ChoiceTile
import de.needanapp.design.components.ListRow
import de.needanapp.design.components.SettingsGroup
import de.needanapp.design.components.SignalCircularProgressIndicator
import de.needanapp.design.components.StatusTile
import de.needanapp.design.components.StatusTone
import de.needanapp.design.components.SwitchTile
import de.needanapp.design.components.TextFieldRow
import de.needanapp.design.components.TileCard
import de.needanapp.design.components.scrollbar
import de.needanapp.needafile.R
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.inBackground
import de.needanapp.needafile.discovery.DiscoveredServer
import de.needanapp.needafile.errors.describeError
import de.needanapp.needafile.remote.AUTH_KEY
import de.needanapp.needafile.remote.AUTH_PASSWORD
import de.needanapp.needafile.remote.FTP_TLS_EXPLICIT
import de.needanapp.needafile.remote.FTP_TLS_IMPLICIT
import de.needanapp.needafile.remote.NeedsTrustDecision
import de.needanapp.needafile.remote.PlainFtpRequiredException
import de.needanapp.needafile.remote.RemoteConnections
import de.needanapp.needafile.remote.RemoteServer
import de.needanapp.needafile.remote.SERVER_TYPE_FTP
import de.needanapp.needafile.remote.SERVER_TYPE_SFTP
import de.needanapp.needafile.remote.SERVER_TYPE_SMB
import de.needanapp.needafile.remote.SERVER_TYPE_WEBDAV
import de.needanapp.needafile.remote.findTrustDecision

/** The server being edited, or a new one when [server] is null; [openAfterwards] browses it once saved. */
data class ServerEditorRequest(val server: RemoteServer?, val openAfterwards: Boolean)

private sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data object Success : TestState
    data class Failed(val message: String) : TestState
}

/** The form's fields; turns into a [RemoteServer] once they are valid. */
@Stable
class ServerForm(private val existing: RemoteServer?) {
    var type by mutableStateOf(existing?.type ?: SERVER_TYPE_SFTP)
        private set
    var name by mutableStateOf(existing?.name.orEmpty())
    var host by mutableStateOf(existing?.host.orEmpty())
    var port by mutableStateOf((existing?.port ?: RemoteServer.DEFAULT_SFTP_PORT).toString())
    var url by mutableStateOf(existing?.url.orEmpty())
    var username by mutableStateOf(existing?.username.orEmpty())
    var usesKey by mutableStateOf(existing?.authType == AUTH_KEY)
    var password by mutableStateOf(existing?.password.orEmpty())
    var privateKey by mutableStateOf(existing?.privateKey.orEmpty())
    var passphrase by mutableStateOf(existing?.passphrase.orEmpty())
    var initialPath by mutableStateOf(existing?.initialPath?.takeIf { it != "/" }.orEmpty())
    var domain by mutableStateOf(existing?.domain.orEmpty())
    var share by mutableStateOf(existing?.share.orEmpty())
    var ftpTls by mutableStateOf(existing?.ftpTls ?: FTP_TLS_EXPLICIT)
        private set
    var allowPlain by mutableStateOf(existing?.allowPlain ?: false)

    /** Shown below the fields once the user tried to save or test. */
    var showErrors by mutableStateOf(false)

    val isNew get() = existing == null

    /** Takes over what a server announced on the network; credentials are left to the user. */
    fun applyDiscovered(server: DiscoveredServer) {
        selectType(server.type)
        name = server.name
        if (server.type == SERVER_TYPE_WEBDAV) {
            val scheme = if (server.isSecure) "https" else "http"
            val isDefaultPort = server.port == if (server.isSecure) HTTPS_PORT else HTTP_PORT
            val path = server.path.takeIf { it.isNotEmpty() }?.let { if (it.startsWith("/")) it else "/$it" }.orEmpty()
            url = "$scheme://${server.host}" + (if (isDefaultPort) "" else ":${server.port}") + path
        } else {
            host = server.host
            port = server.port.toString()
        }
    }

    fun selectType(newType: String) {
        // keep a port the user typed, but follow the protocol's default otherwise
        if (port.isEmpty() || port.toIntOrNull() == defaultPort(type, ftpTls)) {
            port = defaultPort(newType, ftpTls).toString()
        }
        type = newType
    }

    fun selectFtpTls(mode: Int) {
        if (port.isEmpty() || port.toIntOrNull() == defaultPort(type, ftpTls)) {
            port = defaultPort(type, mode).toString()
        }
        ftpTls = mode
    }

    private val parsedUrl get() = Uri.parse(url.trim())

    val urlError get() = if (type == SERVER_TYPE_WEBDAV && (parsedUrl.scheme !in WEB_SCHEMES || parsedUrl.host.isNullOrEmpty())) R.string.url_required else null
    val hostError get() = if (type != SERVER_TYPE_WEBDAV && host.isBlank()) R.string.server_field_required else null
    val portError get() = if (type != SERVER_TYPE_WEBDAV && port.isNotEmpty() && port.toIntOrNull() !in VALID_PORTS) R.string.server_port_invalid else null
    val usernameError get() = if (type == SERVER_TYPE_SFTP && username.isBlank()) R.string.server_field_required else null
    val keyError get() = if (type == SERVER_TYPE_SFTP && usesKey && privateKey.isBlank()) R.string.server_field_required else null

    val isValid get() = listOf(urlError, hostError, portError, usernameError, keyError).all { it == null }

    fun build(): RemoteServer? {
        if (!isValid) {
            showErrors = true
            return null
        }

        val server = (existing?.copy() ?: RemoteServer()).apply {
            name = this@ServerForm.name.trim()
            type = this@ServerForm.type
            username = this@ServerForm.username.trim()
            host = this@ServerForm.host.trim()
            port = this@ServerForm.port.toIntOrNull() ?: defaultPort(type, this@ServerForm.ftpTls)
            authType = AUTH_PASSWORD
            password = this@ServerForm.password
            privateKey = ""
            passphrase = ""
            domain = ""
            share = ""
            url = ""
            ftpTls = FTP_TLS_EXPLICIT
            allowPlain = false
        }
        when (type) {
            SERVER_TYPE_WEBDAV -> {
                val uri = parsedUrl
                server.url = url.trim()
                server.host = uri.host!!
                server.port = if (uri.port != -1) uri.port else if (uri.scheme == "https") HTTPS_PORT else HTTP_PORT
                server.initialPath = "/"
            }

            SERVER_TYPE_FTP -> {
                server.ftpTls = ftpTls
                server.allowPlain = allowPlain
                server.initialPath = initialPath.trim().ifEmpty { "/" }
            }

            SERVER_TYPE_SMB -> {
                server.domain = domain.trim()
                server.share = share.trim().trim('/', '\\')
                server.initialPath = if (server.share.isEmpty()) "/" else "/${server.share}"
            }

            else -> {
                server.authType = if (usesKey) AUTH_KEY else AUTH_PASSWORD
                server.password = if (usesKey) "" else password
                server.privateKey = if (usesKey) privateKey.trim() else ""
                server.passphrase = if (usesKey) passphrase else ""
                server.initialPath = initialPath.trim().ifEmpty { "/" }
            }
        }
        return server
    }

    companion object {
        const val HTTPS_PORT = 443
        const val HTTP_PORT = 80
        private val VALID_PORTS = 1..65535
        private val WEB_SCHEMES = setOf("https", "http")

        fun defaultPort(serverType: String, ftpTls: Int = FTP_TLS_EXPLICIT) = when (serverType) {
            SERVER_TYPE_SMB -> RemoteServer.DEFAULT_SMB_PORT
            SERVER_TYPE_WEBDAV -> HTTPS_PORT
            SERVER_TYPE_FTP -> if (ftpTls == FTP_TLS_IMPLICIT) RemoteServer.DEFAULT_FTPS_IMPLICIT_PORT else RemoteServer.DEFAULT_FTP_PORT
            else -> RemoteServer.DEFAULT_SFTP_PORT
        }
    }
}

/** Adding or editing a server connection as a grouped form; saving happens in the header. */
@Composable
fun ServerEditor(form: ServerForm, contentPadding: PaddingValues, nestedScrollConnection: NestedScrollConnection) {
    val activity = LocalActivity.current as? BaseActivity ?: return
    var test by remember { mutableStateOf<TestState>(TestState.Idle) }
    var trustPrompt by remember { mutableStateOf<NeedsTrustDecision?>(null) }
    var isDiscovering by remember { mutableStateOf(false) }
    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        inBackground {
            val text = runCatching { activity.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            activity.runOnUiThread { text?.let { form.privateKey = it.trim() } }
        }
    }

    fun error(res: Int?) = if (form.showErrors && res != null) activity.getString(res) else null

    fun runTest() {
        val server = form.build() ?: return
        test = TestState.Running
        inBackground {
            val connection = RemoteConnections.create(activity, server)
            val result = try {
                connection.testConnection()
                null
            } catch (e: Exception) {
                e
            } finally {
                connection.close()
            }
            activity.runOnUiThread {
                val trustDecision = result?.findTrustDecision()
                test = when {
                    result == null -> TestState.Success
                    trustDecision != null -> {
                        // the user decides about an unknown or changed server key or certificate, then the test runs again
                        trustPrompt = trustDecision
                        TestState.Idle
                    }

                    else -> TestState.Failed(activity.getString(R.string.connection_failed, activity.describeError(result)))
                }
            }
        }
    }

    val type = form.type
    val isSftp = type == SERVER_TYPE_SFTP
    val isSmb = type == SERVER_TYPE_SMB
    val isWebDav = type == SERVER_TYPE_WEBDAV
    val isFtp = type == SERVER_TYPE_FTP
    val noCorrection = KeyboardOptions(autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None)
    val cardPadding = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)

    // edge to edge the window doesn't shrink for the keyboard; the list does, so the focused field scrolls above it
    val listState = rememberLazyListState()
    LazyColumn(
        state = listState,
        contentPadding = contentPadding,
        modifier = Modifier.fillMaxSize().imePadding().scrollbar(listState, contentPadding).nestedScroll(nestedScrollConnection),
    ) {
        // finding a server and testing the connection on top, so the result shows without scrolling
        item {
            TileCard(null, cardPadding) {
                if (form.isNew) {
                    line({ m, shape ->
                        ActionTile(
                            stringResource(R.string.discovery_search),
                            { isDiscovering = true },
                            shape,
                            m,
                            Icons.Outlined.TravelExplore,
                            summary = stringResource(R.string.discovery_search_summary),
                            inline = true,
                        )
                    })
                }
                val current = test
                if (current == TestState.Success || current is TestState.Failed) {
                    val status = if (current is TestState.Failed) {
                        CardStatus(current.message, Icons.Outlined.ErrorOutline, StatusTone.ERROR)
                    } else {
                        CardStatus(stringResource(R.string.connection_successful), Icons.Outlined.CheckCircle, StatusTone.POSITIVE)
                    }
                    line({ m, shape -> StatusTile(status, shape, m) })
                }
                line({ m, shape ->
                    ActionTile(
                        stringResource(if (current == TestState.Running) R.string.testing_connection else R.string.test_connection),
                        ::runTest,
                        shape,
                        m,
                        Icons.Outlined.NetworkCheck,
                        inline = true,
                        enabled = current != TestState.Running,
                        trailing = if (current == TestState.Running) {
                            { SignalCircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                        } else {
                            null
                        },
                    )
                })
            }
        }

        item {
            TileCard(stringResource(R.string.server_protocol), cardPadding) {
                line(
                    listOf(SERVER_TYPE_SFTP, SERVER_TYPE_SMB, SERVER_TYPE_WEBDAV, SERVER_TYPE_FTP).map { option ->
                        { m, shape ->
                            ChoiceTile(
                                stringResource(protocolLabel(option)),
                                type == option,
                                {
                                    form.selectType(option)
                                    test = TestState.Idle
                                },
                                shape,
                                m,
                                protocolIcon(option),
                                stacked = true,
                                iconInCircle = true,
                                summary = if (option == SERVER_TYPE_WEBDAV) {
                                    stringResource(R.string.webdav_scheme)
                                } else {
                                    stringResource(R.string.server_port_summary, ServerForm.defaultPort(option, form.ftpTls))
                                },
                            )
                        }
                    },
                )
            }
        }

        item {
            SettingsGroup(null) {
                row {
                    TextFieldRow(it, form.name, { value -> form.name = value }, stringResource(R.string.server_display_name))
                }
            }
        }

        item {
            SettingsGroup(stringResource(R.string.server_connection)) {
                if (isWebDav) {
                    row {
                        TextFieldRow(
                            it, form.url, { value -> form.url = value }, stringResource(R.string.server_url_hint),
                            error = error(form.urlError),
                            keyboardOptions = noCorrection.copy(keyboardType = KeyboardType.Uri),
                        )
                    }
                } else {
                    row {
                        TextFieldRow(
                            it, form.host, { value -> form.host = value }, stringResource(R.string.server_host),
                            error = error(form.hostError),
                            keyboardOptions = noCorrection.copy(keyboardType = KeyboardType.Uri),
                        )
                    }
                    row {
                        TextFieldRow(
                            it, form.port, { value -> form.port = value.filter(Char::isDigit) }, stringResource(R.string.server_port),
                            error = error(form.portError),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
                if (isSmb) {
                    row { TextFieldRow(it, form.share, { value -> form.share = value }, stringResource(R.string.server_share), keyboardOptions = noCorrection) }
                    row { TextFieldRow(it, form.domain, { value -> form.domain = value }, stringResource(R.string.server_domain), keyboardOptions = noCorrection) }
                }
                if (isSftp || isFtp) {
                    row { TextFieldRow(it, form.initialPath, { value -> form.initialPath = value }, stringResource(R.string.server_initial_path), placeholder = "/", keyboardOptions = noCorrection) }
                }
            }
        }

        if (isFtp) {
            item {
                TileCard(stringResource(R.string.server_encryption), cardPadding) {
                    line(
                        listOf(FTP_TLS_EXPLICIT, FTP_TLS_IMPLICIT).map { mode ->
                            { m, shape ->
                                ChoiceTile(
                                    stringResource(if (mode == FTP_TLS_IMPLICIT) R.string.server_ftps_implicit else R.string.server_ftps_explicit),
                                    form.ftpTls == mode,
                                    {
                                        form.selectFtpTls(mode)
                                        test = TestState.Idle
                                    },
                                    shape,
                                    m,
                                    Icons.Outlined.Lock,
                                    summary = stringResource(R.string.server_port_summary, ServerForm.defaultPort(SERVER_TYPE_FTP, mode)),
                                )
                            }
                        },
                    )
                    line({ m, shape ->
                        SwitchTile(
                            stringResource(R.string.server_allow_plain),
                            form.allowPlain,
                            { allowed -> form.allowPlain = allowed },
                            shape,
                            m,
                            Icons.Outlined.NoEncryption,
                            summary = stringResource(R.string.server_allow_plain_summary),
                        )
                    })
                }
            }
        }

        if (isSftp) {
            item {
                TileCard(stringResource(R.string.server_authentication), cardPadding) {
                    line(
                        listOf(false, true).map { usesKey ->
                            { m, shape ->
                                ChoiceTile(
                                    stringResource(if (usesKey) R.string.server_auth_key else R.string.server_auth_password),
                                    form.usesKey == usesKey,
                                    { form.usesKey = usesKey },
                                    shape,
                                    m,
                                    if (usesKey) Icons.Outlined.Key else Icons.Outlined.Password,
                                )
                            }
                        },
                    )
                }
            }
        }

        item {
            SettingsGroup(if (isSftp) null else stringResource(R.string.server_authentication)) {
                row {
                    TextFieldRow(
                        it, form.username, { value -> form.username = value },
                        stringResource(
                            when {
                                isSmb -> R.string.server_username_guest
                                isFtp -> R.string.server_username_anonymous
                                else -> R.string.server_username
                            },
                        ),
                        error = error(form.usernameError),
                        keyboardOptions = noCorrection,
                    )
                }
                if (isSftp && form.usesKey) {
                    row {
                        TextFieldRow(
                            it, form.privateKey, { value -> form.privateKey = value }, stringResource(R.string.server_private_key),
                            placeholder = stringResource(R.string.server_paste_private_key),
                            error = error(form.keyError),
                            singleLine = false,
                            minLines = 3,
                            maxLines = 6,
                            keyboardOptions = noCorrection,
                        )
                    }
                    row {
                        ListRow(it, stringResource(R.string.server_pick_key_file), icon = Icons.Outlined.FileOpen, onClick = { pickKey.launch(arrayOf("*/*")) })
                    }
                    row { TextFieldRow(it, form.passphrase, { value -> form.passphrase = value }, stringResource(R.string.server_passphrase), isSecret = true) }
                } else {
                    row { TextFieldRow(it, form.password, { value -> form.password = value }, stringResource(R.string.server_password), isSecret = true) }
                }
            }
        }

        item { BottomPillSpacer() }
    }

    if (isDiscovering) {
        DiscoverySheet(
            onPick = { server ->
                form.applyDiscovered(server)
                test = TestState.Idle
                isDiscovering = false
            },
            onDismiss = { isDiscovering = false },
        )
    }

    trustPrompt?.let { decision ->
        TrustPrompt(
            decision,
            onTrusted = {
                // the form keeps the decision, so saving the server stores it
                if (decision is PlainFtpRequiredException) form.allowPlain = true
                runTest()
            },
            onDismiss = { trustPrompt = null },
        )
    }
}

internal fun protocolLabel(type: String) = when (type) {
    SERVER_TYPE_SMB -> R.string.server_type_smb
    SERVER_TYPE_WEBDAV -> R.string.server_type_webdav
    SERVER_TYPE_FTP -> R.string.server_type_ftp
    else -> R.string.server_type_sftp
}
