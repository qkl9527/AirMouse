package dev.airmouse.tv

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * 在电视上同时提供手机网页和 WebSocket 控制通道。
 *
 * 页面与控制服务使用同一个随机令牌；WebSocket 还校验 Origin，减少同一局域网内
 * 其他网页利用浏览器跨站发起控制连接的机会。服务只绑定本地网络，不开放云端中继。
 */
object ControlServer {
    fun connectedPhoneCount(): Int = clientOutputs.size
    private val modelListing = java.util.concurrent.atomic.AtomicBoolean(false)
    private const val DEFAULT_PORT = 8765
    private const val DEFAULT_HTTPS_PORT = 8766
    private const val MAX_HEADER_BYTES = 8192
    // 选图先由手机压缩；仅 Agent 允许大消息，原遥控协议仍维持 16 KiB 校验。
    private const val MAX_FRAME_BYTES = 768 * 1024
    private const val MAX_CLIENTS = 4
    private const val WEB_SOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    interface Listener {
        fun onAction(action: RemoteAction)
        fun onClientCountChanged(count: Int)
        fun onPartyState(state: JSONObject) = Unit
        fun onAgentApproval(state: JSONObject?) = Unit
        fun foregroundPackage(): String? = null
    }

    private val lifecycleLock = Any()
    private val clients = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val clientOutputs = ConcurrentHashMap<Socket, DataOutputStream>()
    // 同乐身份绑定到已配对 WebSocket，不能只相信每条 JSON 消息自报的 clientId。
    private val partyClientIds = ConcurrentHashMap<Socket, String>()
    // 首次 join 时签发仅保存在该手机浏览器的随机证明，阻止已配对的其他手机只凭公开 ID 冒充玩家。
    private val partyCredentials = ConcurrentHashMap<String, String>()
    private val partyCredentialLock = Any()
    private val pendingPartyDisconnects = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val clientCount = AtomicInteger(0)
    private val workers: ExecutorService = Executors.newCachedThreadPool()
    // 流式增量与最终完整状态按顺序发送，避免并发广播让手机先看到结束、再收到旧片段。
    private val agentNotifications: ExecutorService = Executors.newSingleThreadExecutor()
    private val partyDisconnectScheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "airmouse-party-disconnect").apply { isDaemon = true }
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var serverThread: Thread? = null
    @Volatile private var listener: Listener? = null
    @Volatile private var pairingToken: String? = null
    @Volatile private var pairingUrl: String? = null
    @Volatile private var secureServerSocket: SSLServerSocket? = null
    @Volatile private var secureServerThread: Thread? = null
    @Volatile private var applicationContext: Context? = null
    @Volatile private var secureCertificatePem: String? = null
    @Volatile private var secureCertificateDer: ByteArray? = null
    @Volatile private var secureFingerprint: String? = null
    @Volatile private var pageBytes: ByteArray = ByteArray(0)
    @Volatile private var serverGeneration = 0L

    /**
     * 由无障碍服务在用户启用后启动；Activity 关闭后，系统绑定的服务继续持有接收端。
     */
    fun start(context: Context, actionListener: Listener): String? = synchronized(lifecycleLock) {
        if (serverSocket?.isClosed == false) return@synchronized pairingUrl

        // 每次启动都递增代次；旧连接收尾时只能修改它所属代次的计数。
        val generation = ++serverGeneration

        val server = ServerSocket()
        server.reuseAddress = true
        try {
            // 优先使用固定端口，便于家庭网络调试；端口被占用时改用系统分配端口。
            runCatching { server.bind(InetSocketAddress(DEFAULT_PORT)) }
                .onFailure { server.bind(InetSocketAddress(0)) }
        } catch (error: IOException) {
            runCatching { server.close() }
            TvStatusStore.update { it.copy(pairingUrl = null, message = "无法启动局域网服务：${error.localizedMessage}") }
            return@synchronized null
        }

        val host = findLocalIpv4()
        val token = newToken()
        val url = host?.let { "http://$it:${server.localPort}/?token=$token" }

        serverSocket = server
        listener = actionListener
        applicationContext = context.applicationContext
        AgentSession.initialize(context.applicationContext)
        AgentSession.onChanged = {
            val state = AgentSession.snapshot().toString()
            agentNotifications.execute {
                if (generation == serverGeneration) {
                    clientOutputs.values.forEach { output -> runCatching { writeTextFrame(output, state) } }
                }
            }
        }
        AgentSession.onDelta = { delta ->
            val message = delta.toString()
            agentNotifications.execute {
                if (generation == serverGeneration) {
                    clientOutputs.values.forEach { output -> runCatching { writeTextFrame(output, message) } }
                }
            }
        }
        AgentSession.onApprovalChanged = { approval -> listener?.onAgentApproval(approval) }
        pairingToken = token
        pairingUrl = url
        pageBytes = runCatching { context.applicationContext.assets.open("remote.html").use { it.readBytes() } }
            .getOrElse {
                runCatching { server.close() }
                serverSocket = null
                pairingUrl = null
                TvStatusStore.update { state -> state.copy(pairingUrl = null, message = "找不到内置手机控制页面。") }
                return@synchronized null
            }

        TvStatusStore.update { state ->
            state.copy(
                pairingUrl = url,
                clientCount = 0,
                message = if (url == null) "未发现局域网地址，请检查电视网络。" else null,
            )
        }

        serverThread = Thread({ acceptLoop(server, generation) }, "airmouse-tv-http").apply {
            isDaemon = true
            start()
        }
        url
    }

    fun stop() = synchronized(lifecycleLock) {
        AgentSession.onChanged = null
        AgentSession.onDelta = null
        AgentSession.onApprovalChanged = null
        AgentSession.cancel(clear = true, forgetKey = true)
        serverGeneration++
        pairingToken = null
        pairingUrl = null
        listener = null
        secureCertificatePem = null
        secureCertificateDer = null
        secureFingerprint = null
        runCatching { secureServerSocket?.close() }
        secureServerSocket = null
        secureServerThread = null
        applicationContext = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.toList().forEach { socket -> runCatching { socket.close() } }
        clients.clear()
        clientOutputs.clear()
        partyClientIds.clear()
        synchronized(partyCredentialLock) { partyCredentials.clear() }
        pendingPartyDisconnects.values.forEach { it.cancel(false) }
        pendingPartyDisconnects.clear()
        clientCount.set(0)
        PartyRoom.reset()
        TvStatusStore.update { it.copy(pairingUrl = null, secureFingerprint = null, clientCount = 0) }
    }

    private fun acceptLoop(server: ServerSocket, generation: Long) {
        while (!server.isClosed && generation == serverGeneration) {
            try {
                val socket = server.accept().apply {
                    soTimeout = 120_000
                    tcpNoDelay = true
                }
                if (generation == serverGeneration) {
                    workers.execute { handleConnection(socket, generation) }
                } else {
                    runCatching { socket.close() }
                }
            } catch (_: IOException) {
                if (!server.isClosed && generation == serverGeneration) {
                    TvStatusStore.update { it.copy(message = "局域网连接暂时不可用，请重新打开电视端 App。") }
                }
            }
        }
    }

    private fun handleConnection(socket: Socket, generation: Long) {
        var countedAsClient = false
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val request = readRequest(input) ?: return
            val requestLine = request.first().split(' ')
            if (requestLine.size < 2 || requestLine[0] != "GET") {
                writeHttpError(output, 405, "Method Not Allowed")
                return
            }

            val uri = runCatching { URI(requestLine[1]) }.getOrNull()
            if (uri == null || uri.isAbsolute) {
                writeHttpError(output, 400, "Bad Request")
                return
            }
            val headers = request.drop(1).mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).trim().lowercase(Locale.ROOT) to
                    line.substring(colon + 1).trim()
            }.toMap()
            val query = parseQuery(uri.rawQuery)
            if (!hasValidToken(query["token"])) {
                writeHttpError(output, 403, "Pairing Required")
                return
            }

            when (uri.rawPath ?: "/") {
                "/agent-phone.css", "/agent-phone.js", "/agent-conversation.js", "/agent-provider-ui.js" -> {
                    val name = uri.rawPath.removePrefix("/")
                    val body = applicationContext?.assets?.open(name)?.use { it.readBytes() } ?: return
                    val type = if (name.endsWith(".css")) "text/css" else "application/javascript"
                    output.writeHttpResponse(200, "OK", "$type; charset=utf-8", body)
                }
                "/" -> {
                    output.writeHttpResponse(200, "OK", "text/html; charset=utf-8", pageBytes)
                }

                "/certificate" -> {
                    val format = query["format"] ?: "der"
                    val certificateDer = secureCertificateDer
                    val certificatePem = secureCertificatePem
                    if (certificateDer == null || certificatePem == null) {
                        writeHttpError(output, 404, "Secure Mode Not Prepared")
                    } else {
                        // iOS 用可直接安装的配置描述文件；Android 等设备使用标准 DER 编码证书。
                        val (body, contentType, filename) = when (format) {
                            "profile" -> Triple(
                                appleTrustProfile(certificateDer).toByteArray(StandardCharsets.UTF_8),
                                "application/x-apple-aspen-config",
                                "airmouse-tv-trust.mobileconfig",
                            )
                            "pem" -> Triple(
                                certificatePem.toByteArray(StandardCharsets.US_ASCII),
                                "application/x-pem-file",
                                "airmouse-tv-ca.pem",
                            )
                            else -> Triple(certificateDer, "application/pkix-cert", "airmouse-tv-ca.cer")
                        }
                        output.write(
                            ("HTTP/1.1 200 OK\r\n" +
                                "Content-Type: $contentType\r\n" +
                                "Content-Disposition: attachment; filename=$filename\r\n" +
                                "Content-Length: ${body.size}\r\n" +
                                "Cache-Control: no-store\r\n" +
                                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1),
                        )
                        output.write(body)
                        output.flush()
                    }
                }

                "/ws" -> {
                    val secureTransport = socket is SSLSocket
                    if (!isAllowedOrigin(headers["origin"], secureTransport)) {
                        writeHttpError(output, 403, "Origin Rejected")
                        return
                    }
                    val webSocketAccept = webSocketAcceptValue(headers)
                    if (webSocketAccept == null) {
                        writeHttpError(output, 400, "WebSocket Upgrade Required")
                        return
                    }
            if (!reserveClientSlot(generation)) {
                        writeHttpError(output, 503, "Server Restarting or Too Many Controllers")
                        return
                    }
                    countedAsClient = true
                    writeWebSocketUpgrade(output, webSocketAccept)
            if (!activateClient(socket, output, generation)) return
                    AgentDiagnostics.event("phone", "connected", "controllers=${connectedPhoneCount()}")
                    AgentSession.refreshConnectionState()
                    writeTextFrame(output, "{\"type\":\"status\",\"connected\":true,\"keyboardProtocol\":3}")
                    writeTextFrame(output, AgentSession.snapshot().toString())
                    readWebSocketMessages(socket, input, output)
                }

                else -> writeHttpError(output, 404, "Not Found")
            }
        } catch (_: EOFException) {
            // 浏览器正常断开时，客户端可能在任意帧边界结束连接。
        } catch (_: IOException) {
            // 网络切换或手机锁屏会关闭 TCP；清理逻辑在 finally 中统一执行。
        } catch (_: Exception) {
            // 单个坏请求不能影响仍在服务的其他手机连接。
        } finally {
            runCatching { socket.close() }
            unregisterClient(socket, generation, countedAsClient)
            if (countedAsClient && generation == serverGeneration) {
                AgentDiagnostics.event("phone", "disconnected", "controllers=${connectedPhoneCount()}")
                AgentSession.refreshConnectionState()
            }
        }
    }

    private fun readRequest(input: BufferedInputStream): List<String>? {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (bytes.size() < MAX_HEADER_BYTES) {
            val next = input.read()
            if (next < 0) return null
            bytes.write(next)
            if (next.toByte() == terminator[matched]) {
                matched++
                if (matched == terminator.size) {
                    return String(bytes.toByteArray(), StandardCharsets.ISO_8859_1)
                        .split("\r\n")
                        .filter(String::isNotEmpty)
                }
            } else {
                matched = if (next.toByte() == terminator[0]) 1 else 0
            }
        }
        return null
    }

    /** 只有用户主动请求空中模式时才建立 TLS 身份和监听端口。 */
    private fun prepareSecureMode(socket: Socket, clientId: String): Triple<String, String, String> = synchronized(lifecycleLock) {
        // 只为当前已完成 join 身份校验的手机生成安全地址，proof 从服务端记录取回。
        check(partyClientIds[socket] == clientId) { "手机连接身份尚未完成校验，请刷新控制页后重试。" }
        val partyProof = partyCredentials[clientId] ?: error("手机身份凭据已失效，请重新扫码连接。")
        val expectedPairingUrl = pairingUrl ?: error("电视局域网服务尚未启动。")
        val host = URI(expectedPairingUrl).host ?: error("无法从电视配对地址读取局域网 IP。")
        val token = pairingToken ?: error("电视配对令牌已失效，请重新扫码连接。")
        val context = applicationContext ?: error("电视控制服务上下文已释放，请重新启用服务。")
        var secureServer = secureServerSocket
        var fingerprint: String

        if (secureServer?.isClosed != false) {
            val identity = try {
                LocalTlsIdentity.create(context, host)
            } catch (error: Exception) {
                throw IllegalStateException(
                    "创建电视本地证书失败：${error.localizedMessage ?: error.javaClass.simpleName}",
                    error,
                )
            }
            val tlsContext = SSLContext.getInstance("TLS").apply {
                init(identity.keyManagers, null, SecureRandom())
            }
            val createdServer = tlsContext.serverSocketFactory.createServerSocket() as SSLServerSocket
            createdServer.reuseAddress = true
            try {
                createdServer.bind(InetSocketAddress(DEFAULT_HTTPS_PORT))
            } catch (preferredPortError: IOException) {
                try {
                    // 固定端口被占用时改用临时端口；若两者都失败，把备用端口原因回传给手机。
                    createdServer.bind(InetSocketAddress(0))
                } catch (fallbackPortError: IOException) {
                    runCatching { createdServer.close() }
                    throw IllegalStateException(
                        "HTTPS 端口 $DEFAULT_HTTPS_PORT 和临时端口均无法监听：${fallbackPortError.localizedMessage ?: preferredPortError.localizedMessage}",
                        fallbackPortError,
                    )
                }
            }
            val secureProtocols = createdServer.supportedProtocols
                .filter { it == "TLSv1.3" || it == "TLSv1.2" }
                .toTypedArray()
            check(secureProtocols.isNotEmpty()) { "电视系统没有可用的 TLS 1.2/1.3 协议。" }
            try {
                createdServer.enabledProtocols = secureProtocols
            } catch (error: Exception) {
                runCatching { createdServer.close() }
                throw IllegalStateException("电视系统无法启用 TLS 1.2/1.3：${error.localizedMessage ?: error.javaClass.simpleName}", error)
            }
            secureCertificatePem = identity.caCertificatePem
            secureCertificateDer = identity.caCertificateDer
            fingerprint = identity.fingerprint
            secureFingerprint = identity.fingerprint
            TvStatusStore.update { it.copy(secureFingerprint = identity.fingerprint) }
            secureServerSocket = createdServer
            secureServer = createdServer
            val activeServer = createdServer
            val generation = serverGeneration
            secureServerThread = Thread({ acceptLoop(activeServer, generation) }, "airmouse-tv-https").apply {
                isDaemon = true
                start()
            }
        } else {
            // 复用证书根时返回可在配对页展示的稳定指纹。
            fingerprint = secureFingerprint ?: error("电视安全证书状态丢失，请重启电视控制服务后重试。")
        }

        val activeServer = secureServer ?: error("电视 HTTPS 服务未能保持运行。")
        val encodedClient = URLEncoder.encode(clientId, StandardCharsets.UTF_8.name())
        val encodedProof = URLEncoder.encode(partyProof, StandardCharsets.UTF_8.name())
        val encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8.name())
        val secureUrl = "https://$host:${activeServer.localPort}/?token=$encodedToken&clientId=$encodedClient&partyProof=$encodedProof&mode=air"
        val httpPort = serverSocket?.localPort ?: error("电视 HTTP 配对服务已关闭。")
        val certificateUrl = "http://$host:$httpPort/certificate?token=$encodedToken"
        Triple(secureUrl, certificateUrl, fingerprint)
    }

    /**
     * iOS 通过配置描述文件安装根证书，比把 PEM 文本伪装成 .crt 更容易走系统安装流程。
     * 描述文件只包含公开 CA 证书，不包含电视端私钥；用户仍需在 iOS 设置中启用完整信任。
     */
    private fun appleTrustProfile(certificateDer: ByteArray): String {
        val certificate = Base64.encodeToString(certificateDer, Base64.NO_WRAP)
        // 以 CA 证书内容生成稳定 UUID，重复下载时 iOS 可识别为同一份描述文件。
        val certificateId = UUID.nameUUIDFromBytes(certificateDer).toString()
        val profileId = UUID.nameUUIDFromBytes("airmouse-tv-profile:$certificateId".toByteArray(StandardCharsets.UTF_8)).toString()
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>PayloadContent</key>
              <array>
                <dict>
                  <key>PayloadCertificateFileName</key><string>airmouse-tv-ca.cer</string>
                  <key>PayloadContent</key><data>$certificate</data>
                  <key>PayloadDescription</key><string>用于信任 AirMouse 电视的本地 HTTPS 连接。</string>
                  <key>PayloadDisplayName</key><string>AirMouse 电视信任证书</string>
                  <key>PayloadIdentifier</key><string>dev.airmouse.tv.localca</string>
                  <key>PayloadType</key><string>com.apple.security.root</string>
                  <key>PayloadUUID</key><string>$certificateId</string>
                  <key>PayloadVersion</key><integer>1</integer>
                </dict>
              </array>
              <key>PayloadDescription</key><string>安装后可在局域网中安全使用 AirMouse 空中飞鼠。</string>
              <key>PayloadDisplayName</key><string>AirMouse 电视安全连接</string>
              <key>PayloadIdentifier</key><string>dev.airmouse.tv.trust.$profileId</string>
              <key>PayloadOrganization</key><string>AirMouse</string>
              <key>PayloadType</key><string>Configuration</string>
              <key>PayloadUUID</key><string>$profileId</string>
              <key>PayloadVersion</key><integer>1</integer>
            </dict>
            </plist>
        """.trimIndent()
    }

    private fun webSocketAcceptValue(headers: Map<String, String>): String? {
        if (!headers["upgrade"].equals("websocket", ignoreCase = true)) return null
        val connection = headers["connection"] ?: return null
        if (connection.split(',').none { it.trim().equals("upgrade", ignoreCase = true) }) return null
        if (headers["sec-websocket-version"] != "13") return null
        val key = headers["sec-websocket-key"] ?: return null
        return Base64.encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WEB_SOCKET_GUID).toByteArray(StandardCharsets.ISO_8859_1)),
            Base64.NO_WRAP,
        )
    }

    private fun writeWebSocketUpgrade(output: DataOutputStream, accept: String) {
        output.write(
            ("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1),
        )
        output.flush()
    }

    private fun readWebSocketMessages(socket: Socket, input: BufferedInputStream, output: DataOutputStream) {
        val frames = DataInputStream(input)
        while (!Thread.currentThread().isInterrupted) {
            val first = frames.readUnsignedByte()
            val second = frames.readUnsignedByte()
            val isFinal = first and 0x80 != 0
            val opcode = first and 0x0f
            val isMasked = second and 0x80 != 0
            // 必须按帧头中的长度标记只分支一次。若先读 16 位长度得到 127，
            // 再用独立 if 判断 127，会把掩码和正文误当成 64 位长度，直接断开连接。
            // 这里的边界是整条 JSON 的 UTF-8 字节数，并不是 API Key 的字符数。
            val length = when (val lengthMarker = second and 0x7f) {
                126 -> frames.readUnsignedShort().toLong()
                127 -> frames.readLong()
                else -> lengthMarker.toLong()
            }
            if (!isFinal || !isMasked || length < 0 || length > MAX_FRAME_BYTES) {
                writeCloseFrame(output, 1009)
                return
            }

            val mask = ByteArray(4)
            frames.readFully(mask)
            val payload = ByteArray(length.toInt())
            frames.readFully(payload)
            for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()

            when (opcode) {
                0x1 -> {
                    val message = payload.toString(StandardCharsets.UTF_8)
                    val json = runCatching { JSONObject(message) }.getOrNull()
                    if (json != null && json.optString("type").startsWith("agent_")) {
                        handleAgentMessage(json, output, socket)
                        continue
                    }
                    when (val action = ControlMessage.decode(message)) {
                        is RemoteAction.ListApps -> writeTextFrame(output, listAppsMessage(action.requestId))

                        is RemoteAction.Text -> {
                            // 网络接收与系统输入完成是两个阶段。先向发起手机确认收件，
                            // 即使系统无障碍接口响应慢，也不会让用户对着无反馈的按钮等待。
                            action.requestId?.let { requestId ->
                                writeTextFrame(output, JSONObject()
                                    .put("type", "text_received")
                                    .put("requestId", requestId)
                                    .toString())
                            }
                            runCatching { listener?.onAction(action) }
                        }

                        is RemoteAction.JoinParty -> {
                            joinParty(socket, output, action)
                        }

                        is RemoteAction.StartQuiz -> withPartyIdentity(socket, output, action.clientId) { id ->
                            PartyRoom.startQuiz(id, action.question)
                        }

                        is RemoteAction.Buzz -> withPartyIdentity(socket, output, action.clientId) { id -> PartyRoom.buzz(id) }

                        is RemoteAction.StartVote -> withPartyIdentity(socket, output, action.clientId) { id ->
                            PartyRoom.startVote(id, action.options)
                        }

                        is RemoteAction.Vote -> withPartyIdentity(socket, output, action.clientId) { id ->
                            PartyRoom.vote(id, action.optionIndex)
                        }

                        is RemoteAction.EndParty -> withPartyIdentity(socket, output, action.clientId) { id -> PartyRoom.end(id) }

                        is RemoteAction.EnableAirMouse -> {
                            val secureDetails = try {
                                prepareSecureMode(socket, action.clientId)
                            } catch (error: Exception) {
                                Log.e("AirMouseControl", "准备电视 HTTPS 安全连接失败", error)
                                writeTextFrame(output, JSONObject()
                                    .put("type", "notice")
                                    .put("message", "无法准备电视安全连接：${error.message ?: error.javaClass.simpleName}。普通触控遥控仍可用。")
                                    .toString())
                                null
                            }
                            if (secureDetails != null) {
                                writeTextFrame(output, JSONObject().apply {
                                    put("type", "secure_ready")
                                    put("secureUrl", secureDetails.first)
                                    put("certificateUrl", secureDetails.second)
                                    put("fingerprint", secureDetails.third)
                                }.toString())
                            }
                        }

                        null -> Unit
                        else -> runCatching { listener?.onAction(action) }
                    }
                }

                0x8 -> {
                    writeFrame(output, 0x8, payload)
                    return
                }

                0x9 -> writeFrame(output, 0xA, payload)
                0xA -> Unit
                else -> {
                    writeCloseFrame(output, 1003)
                    return
                }
            }
        }
    }

    private fun writeTextFrame(output: DataOutputStream, message: String) {
        writeFrame(output, 0x1, message.toByteArray(StandardCharsets.UTF_8))
    }

    /** Agent 复用已配对 WS；Key 不出现在 URL、日志或回包中，持久化只走加密配置。 */
    private fun handleAgentMessage(json: JSONObject, output: DataOutputStream, socket: Socket) {
        val id = json.optString("requestId")
        if (!id.matches(Regex("[A-Za-z0-9_-]{1,96}"))) return
        if (json.optString("type") == "agent_debug_get") {
            val result = JSONObject().put("type", "agent_result").put("requestId", id).put("ok", true)
                .put("data", AgentDiagnostics.snapshot())
            writeTextFrame(output, result.toString())
            return
        }
        if(json.optString("type")=="agent_provider_models") {
            // 读取模型列表可能较慢，不能占住遥控 WebSocket 接收循环。
            if(!modelListing.compareAndSet(false,true)) {
                writeTextFrame(output,JSONObject().put("type","agent_result").put("requestId",id).put("ok",false).put("message","正在读取模型列表，请稍候。").toString())
                return
            }
            val serverVersion=serverGeneration
            Thread({
                try {
                    val result=runCatching {AgentModelClient.models(AgentSession.modelsRoute(json.optString("providerId")))}
                    val response=JSONObject().put("type","agent_result").put("requestId",id).put("ok",result.isSuccess)
                    result.onSuccess {response.put("data",it)}.onFailure {response.put("message",if(it is AgentModelClient.Failure)it.message else "读取模型列表失败，请检查地址与网络，或手动添加模型。")}
                    if(serverVersion==serverGeneration)runCatching {writeTextFrame(output,response.toString())}
                } finally {modelListing.set(false)}
            },"airmouse-agent-models").start()
            return
        }
        if(json.optString("type") in setOf("agent_provider_save","agent_provider_delete","agent_provider_activate")) {
            val result=AgentSession.updateProvider(json.optString("type"),json)
                .put("type","agent_result").put("requestId",id)
            writeTextFrame(output,result.toString())
            writeTextFrame(output,AgentSession.snapshot().toString())
            return
        }
        val error = when (json.optString("type")) {
            "agent_access_mode" -> AgentSession.setAccessMode(json.optString("value"), json.optString("expectedMode"))
            "agent_debug" -> {
                val value = json.opt("enabled")
                if (value !is Boolean) "调试开关参数无效。"
                else AgentDiagnostics.setEnabled(value).also { if (it == null) AgentSession.refreshConnectionState() }
            }
            "agent_configure" -> "模型配置已升级为多提供商，请刷新手机网页后配置。"
            "agent_text_size" -> {
                val value=json.opt("value")
                if(value !is Number || value.toDouble()!=value.toInt().toDouble()) "字号必须为整数。"
                else AgentSession.setTextSize(value.toInt())
            }
            "agent_stream" -> {
                val enabled = json.opt("enabled")
                val context = applicationContext
                if (enabled !is Boolean || context == null) "无法保存流式输出设置，请重新连接电视。"
                else { AgentSession.setStreaming(context, enabled); null }
            }
            "agent_send" -> {
                val owner = partyClientIds[socket]
                if (owner == null) "手机身份尚未完成配对，请稍后重试。"
                else AgentSession.send(id, json.optString("text").trim(), json.optString("image"), owner,
                    AgentTools.context(json.optJSONObject("context") ?: JSONObject(), installedApps()), json.optString("sessionId"))
            }
            "agent_decide" -> {
                val owner = partyClientIds[socket]
                val decision = json.opt("allow")
                if (owner == null || decision !is Boolean) "手机身份或确认参数无效。"
                else AgentSession.decide(owner, json.optString("approvalId"), decision)
            }
            "agent_stop" -> { AgentSession.cancel(); null }
            // 兼容旧版“新对话”入口：归档旧会话，不再清除历史。
            "agent_clear" -> AgentSession.selectSession()
            "agent_session_new" -> {
                val temporary=json.opt("temporary")
                if(temporary !is Boolean) "会话类型无效。" else AgentSession.selectSession(temporary=temporary, expectedSessionId=json.optString("expectedSessionId"))
            }
            "agent_session_switch" -> AgentSession.selectSession(id=json.optString("sessionId"), expectedSessionId=json.optString("expectedSessionId"))
            "agent_session_delete" -> AgentSession.deleteSession(json.optString("sessionId"))
            "agent_forget" -> AgentSession.forgetConfigurationKey()
            "agent_open" -> { listener?.onAction(RemoteAction.OpenAgent(id)); null }
            "agent_sync" -> null
            "agent_latest" -> { AgentSession.scrollToLatestAnswer(); null }
            else -> "未知 Agent 操作，请更新电视 App。"
        }
        val result=JSONObject().put("type", "agent_result").put("requestId", id)
            .put("ok", error == null).put("message", error ?: "已收到")
        writeTextFrame(output,result.toString())
        writeTextFrame(output, AgentSession.snapshot().toString())
    }

    private fun sendPartyTransition(output: DataOutputStream, transition: PartyRoom.Transition) {
        val state = transition.state
        if (state == null) {
            writeTextFrame(output, JSONObject().put("type", "party_error").put("message", transition.error ?: "活动暂时无法开始。").toString())
            return
        }
        broadcastPartyState(state)
    }

    private fun broadcastPartyState(state: JSONObject) {
        clients.forEach { client ->
            clientOutputs[client]?.let { clientOutput -> runCatching { writeTextFrame(clientOutput, state.toString()) } }
        }
        runCatching { listener?.onPartyState(state) }
    }

    /** 新玩家第一次加入时签发证明；后续连接必须带回证明，不能用公开的 hostId 冒名加入。 */
    private fun joinParty(socket: Socket, output: DataOutputStream, action: RemoteAction.JoinParty) {
        val existingSocketId = partyClientIds[socket]
        if (existingSocketId != null && existingSocketId != action.clientId) {
            sendPartyError(output, "此连接的参与身份已锁定，请重新连接后加入。")
            return
        }

        val proof = synchronized(partyCredentialLock) {
            val knownProof = partyCredentials[action.clientId]
            when {
                knownProof == null -> newToken().also { partyCredentials[action.clientId] = it }
                constantTimeEquals(knownProof, action.proof) -> knownProof
                else -> null
            }
        }
        if (proof == null) {
            sendPartyError(output, "参与身份验证失败，请刷新手机页面后重试。")
            return
        }
        synchronized(lifecycleLock) {
            partyClientIds.putIfAbsent(socket, action.clientId)
            pendingPartyDisconnects.remove(action.clientId)?.cancel(false)
        }
        val state = PartyRoom.join(action.clientId)
        writeTextFrame(output, JSONObject()
            .put("type", "party_identity")
            .put("clientId", action.clientId)
            .put("proof", proof)
            .toString())
        writeTextFrame(output, state.toString())
    }

    private fun sendPartyError(output: DataOutputStream, message: String) {
        writeTextFrame(output, JSONObject().put("type", "party_error").put("message", message).toString())
    }

    private fun constantTimeEquals(expected: String, candidate: String): Boolean = MessageDigest.isEqual(
        expected.toByteArray(StandardCharsets.UTF_8),
        candidate.toByteArray(StandardCharsets.UTF_8),
    )

    /** 每个房间操作都必须使用该连接初次 join 时绑定的身份，防止伪造主持人或刷多票。 */
    private fun withPartyIdentity(
        socket: Socket,
        output: DataOutputStream,
        claimedClientId: String,
        transition: (String) -> PartyRoom.Transition,
    ) {
        val boundClientId = partyClientIds[socket]
        if (boundClientId == null || boundClientId != claimedClientId) {
            writeTextFrame(output, JSONObject().put("type", "party_error").put("message", "连接身份未确认，请重新连接电视。").toString())
            return
        }
        sendPartyTransition(output, transition(boundClientId))
    }

    private fun writeCloseFrame(output: DataOutputStream, code: Int) {
        writeFrame(output, 0x8, byteArrayOf((code shr 8).toByte(), code.toByte()))
    }

    private fun writeFrame(output: DataOutputStream, opcode: Int, payload: ByteArray) {
        synchronized(output) {
            output.writeByte(0x80 or opcode)
            when {
                payload.size < 126 -> output.writeByte(payload.size)
                payload.size <= 0xffff -> {
                    output.writeByte(126)
                    output.writeShort(payload.size)
                }

                else -> {
                    output.writeByte(127)
                    output.writeLong(payload.size.toLong())
                }
            }
            output.write(payload)
            output.flush()
        }
    }

    /** 把设备不支持的动作及时反馈到手机页，便于用户知道发生了什么。 */
    fun notifyControllers(message: String) {
        val safeMessage = JSONObject().put("type", "notice").put("message", message).toString()
        clientOutputs.values.forEach { output -> runCatching { writeTextFrame(output, safeMessage) } }
    }

    /** 操作结果携带请求号广播；只有发起该请求的手机会认领这条结果。 */
    fun notifyActionResult(requestId: String, success: Boolean, message: String) {
        if (AgentTools.complete(requestId, success, message)) return
        val response = JSONObject()
            .put("type", "action_result")
            .put("requestId", requestId)
            .put("success", success)
            .put("message", message)
            .toString()
        clientOutputs.values.forEach { output -> runCatching { writeTextFrame(output, response) } }
    }

    private fun listAppsMessage(requestId: String): String {
        return JSONObject().put("type", "apps").put("requestId", requestId).put("items", installedApps()).toString()
    }

    private fun installedApps(): org.json.JSONArray {
        val context = applicationContext ?: return org.json.JSONArray()
        @Suppress("DEPRECATION")
        val apps = listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)
            .flatMap { category -> context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0) }
            .mapNotNull { resolve ->
                val activity = resolve.activityInfo ?: return@mapNotNull null
                JSONObject()
                    .put("packageName", activity.packageName)
                    .put("label", resolve.loadLabel(context.packageManager).toString())
            }
            .distinctBy { it.optString("packageName") }
            .sortedBy { it.optString("label").lowercase(Locale.ROOT) }
        return org.json.JSONArray(apps)
    }

    fun agentForegroundPackage(): String? = listener?.foregroundPackage()

    /** 此入口只接收 AgentTools 已批准的步骤，不能由网络直接给出执行计划。 */
    fun executeAgentStep(step: AgentTools.Step, requestId: String, owner: String, allowed: () -> Boolean) {
        if (!allowed()) { AgentTools.complete(requestId, false, "操作已取消。"); return }
        val command = JSONObject(step.command.toString()).put("requestId", requestId).put("clientId", owner)
        if (command.has("partySessionId") && command.optString("partySessionId") != PartyRoom.snapshot().optString("sessionId")) {
            AgentTools.complete(requestId, false, "同乐活动已更换，本次确认不适用于新活动。"); return
        }
        val action = if (command.optString("type") == "agent_drag") {
            RemoteAction.AgentDrag(command.getDouble("dx").toFloat(), command.getDouble("dy").toFloat())
        } else ControlMessage.decode(command.toString())
        val transition = when (action) {
            is RemoteAction.StartQuiz -> PartyRoom.startQuiz(owner, action.question)
            is RemoteAction.StartVote -> PartyRoom.startVote(owner, action.options)
            is RemoteAction.Buzz -> PartyRoom.buzz(owner)
            is RemoteAction.Vote -> PartyRoom.vote(owner, action.optionIndex)
            is RemoteAction.EndParty -> PartyRoom.end(owner)
            else -> null
        }
        if (transition != null) {
            transition.state?.let(::broadcastPartyState)
            AgentTools.complete(requestId, transition.error == null, transition.error ?: "同乐操作已提交。")
        } else if (action == null || listener == null) {
            AgentTools.complete(requestId, false, "电视服务不可用或操作参数无效。")
        } else listener?.onAction(RemoteAction.AgentCommand(action, requestId, step.targetPackage, allowed))
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> = rawQuery
        ?.split('&')
        ?.mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator < 0) null else runCatching {
                URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8.name()) to
                    URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8.name())
            }.getOrNull()
        }
        ?.toMap()
        ?: emptyMap()

    private fun hasValidToken(candidate: String?): Boolean {
        val expected = pairingToken ?: return false
        if (candidate == null) return false
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            candidate.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun isAllowedOrigin(origin: String?, secureTransport: Boolean): Boolean {
        // 非浏览器客户端可以没有 Origin；网页必须匹配当前传输协议、电视地址和端口。
        if (origin.isNullOrBlank()) return true
        val actual = runCatching { URI(origin) }.getOrNull() ?: return false
        val expected = runCatching { URI(pairingUrl ?: return false) }.getOrNull() ?: return false
        val expectedScheme = if (secureTransport) "https" else "http"
        val expectedPort = if (secureTransport) secureServerSocket?.localPort else serverSocket?.localPort
        return actual.scheme == expectedScheme && actual.host == expected.host && actual.port == expectedPort
    }

    private fun writeHttpError(output: DataOutputStream, code: Int, reason: String) {
        output.writeHttpResponse(code, reason, "text/plain; charset=utf-8", reason.toByteArray(StandardCharsets.UTF_8))
    }

    private fun DataOutputStream.writeHttpResponse(code: Int, reason: String, contentType: String, body: ByteArray) {
        write(
            ("HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(StandardCharsets.ISO_8859_1),
        )
        write(body)
        flush()
    }

    private fun findLocalIpv4(): String? = runCatching {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { network -> runCatching { network.isUp && !network.isLoopback }.getOrDefault(false) }
            .sortedBy { network -> if (network.name.lowercase(Locale.ROOT).startsWith("wlan")) 0 else 1 }
        interfaces.asSequence()
            .flatMap { network -> Collections.list(network.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { address -> !address.isLoopbackAddress && !address.hostAddress.orEmpty().startsWith("169.254.") }
            ?.hostAddress
    }.getOrNull()

    private fun newToken(): String {
        val random = ByteArray(24).also(SecureRandom()::nextBytes)
        return Base64.encodeToString(random, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** 握手前只占用名额；socket 不加入广播集合，避免 101 响应前收到 WebSocket 帧。 */
    private fun reserveClientSlot(generation: Long): Boolean =
        synchronized(lifecycleLock) {
            if (generation != serverGeneration || serverSocket?.isClosed != false) return@synchronized false
            val count = clientCount.incrementAndGet()
            if (count > MAX_CLIENTS) {
                clientCount.decrementAndGet()
                return@synchronized false
            }
            TvStatusStore.update { it.copy(clientCount = count) }
            runCatching { listener?.onClientCountChanged(count) }
            true
        }

    /** 只有 HTTP 101 握手写完后才向其他客户端公开新 socket。 */
    private fun activateClient(socket: Socket, output: DataOutputStream, generation: Long): Boolean =
        synchronized(lifecycleLock) {
            if (generation != serverGeneration || serverSocket?.isClosed != false || socket.isClosed) return@synchronized false
            clients.add(socket)
            clientOutputs[socket] = output
            true
        }

    private fun unregisterClient(socket: Socket, generation: Long, counted: Boolean) =
        synchronized(lifecycleLock) {
            clients.remove(socket)
            clientOutputs.remove(socket)
            val disconnectedPartyId = partyClientIds.remove(socket)
            if (disconnectedPartyId != null) {
                // 空中飞鼠会用 HTTPS 替换 HTTP 页面；留一点重连宽限期，避免切换时丢失主持身份。
                if (disconnectedPartyId !in partyClientIds.values) schedulePartyDisconnect(disconnectedPartyId)
            }
            if (counted && generation == serverGeneration) {
                val count = clientCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                TvStatusStore.update { it.copy(clientCount = count) }
                runCatching { listener?.onClientCountChanged(count) }
            }
        }

    private fun schedulePartyDisconnect(clientId: String) {
        pendingPartyDisconnects.remove(clientId)?.cancel(false)
        pendingPartyDisconnects[clientId] = partyDisconnectScheduler.schedule({
            synchronized(lifecycleLock) {
                pendingPartyDisconnects.remove(clientId)
                if (clientId in partyClientIds.values) return@synchronized
                val transition = PartyRoom.onDisconnect(clientId, partyClientIds.values.toList())
                transition.state?.let(::broadcastPartyState)
            }
        }, 15, TimeUnit.SECONDS)
    }
}
