package dev.airmouse.tv

import android.content.Context
import android.util.Base64
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
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 在电视上同时提供手机网页和 WebSocket 控制通道。
 *
 * 页面与控制服务使用同一个随机令牌；WebSocket 还校验 Origin，减少同一局域网内
 * 其他网页利用浏览器跨站发起控制连接的机会。服务只绑定本地网络，不开放云端中继。
 */
object ControlServer {
    private const val DEFAULT_PORT = 8765
    private const val MAX_HEADER_BYTES = 8192
    private const val MAX_FRAME_BYTES = 16 * 1024
    private const val MAX_CLIENTS = 4
    private const val WEB_SOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    interface Listener {
        fun onAction(action: RemoteAction)
        fun onClientCountChanged(count: Int)
    }

    private val lifecycleLock = Any()
    private val clients = Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())
    private val clientOutputs = ConcurrentHashMap<Socket, DataOutputStream>()
    private val clientCount = AtomicInteger(0)
    private val workers: ExecutorService = Executors.newCachedThreadPool()

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var serverThread: Thread? = null
    @Volatile private var listener: Listener? = null
    @Volatile private var pairingToken: String? = null
    @Volatile private var pairingUrl: String? = null
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
        serverGeneration++
        pairingToken = null
        pairingUrl = null
        listener = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        clients.toList().forEach { socket -> runCatching { socket.close() } }
        clients.clear()
        clientOutputs.clear()
        clientCount.set(0)
        TvStatusStore.update { it.copy(pairingUrl = null, clientCount = 0) }
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
                "/" -> {
                    output.writeHttpResponse(200, "OK", "text/html; charset=utf-8", pageBytes)
                }

                "/ws" -> {
                    if (!isAllowedOrigin(headers["origin"])) {
                        writeHttpError(output, 403, "Origin Rejected")
                        return
                    }
                    val webSocketAccept = webSocketAcceptValue(headers)
                    if (webSocketAccept == null) {
                        writeHttpError(output, 400, "WebSocket Upgrade Required")
                        return
                    }
                    if (!registerClient(socket, output, generation)) {
                        writeHttpError(output, 503, "Server Restarting or Too Many Controllers")
                        return
                    }
                    countedAsClient = true
                    writeWebSocketUpgrade(output, webSocketAccept)
                    writeTextFrame(output, "{\"type\":\"status\",\"connected\":true}")
                    readWebSocketMessages(input, output)
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

    private fun readWebSocketMessages(input: BufferedInputStream, output: DataOutputStream) {
        val frames = DataInputStream(input)
        while (!Thread.currentThread().isInterrupted) {
            val first = frames.readUnsignedByte()
            val second = frames.readUnsignedByte()
            val isFinal = first and 0x80 != 0
            val opcode = first and 0x0f
            val isMasked = second and 0x80 != 0
            var length = (second and 0x7f).toLong()

            if (length == 126L) length = frames.readUnsignedShort().toLong()
            if (length == 127L) length = frames.readLong()
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
                    ControlMessage.decode(message)?.let { action ->
                        runCatching { listener?.onAction(action) }
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

    private fun isAllowedOrigin(origin: String?): Boolean {
        // 非浏览器客户端可以没有 Origin，但网页必须与二维码地址同源。
        if (origin.isNullOrBlank()) return true
        val expectedUrl = pairingUrl ?: return false
        val actual = runCatching { URI(origin) }.getOrNull() ?: return false
        val expected = runCatching { URI(expectedUrl) }.getOrNull() ?: return false
        return actual.scheme == "http" && actual.host == expected.host && actual.port == expected.port
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

    private fun registerClient(socket: Socket, output: DataOutputStream, generation: Long): Boolean =
        synchronized(lifecycleLock) {
            if (generation != serverGeneration || serverSocket?.isClosed != false) return@synchronized false
            val count = clientCount.incrementAndGet()
            if (count > MAX_CLIENTS) {
                clientCount.decrementAndGet()
                return@synchronized false
            }
            clients.add(socket)
            clientOutputs[socket] = output
            TvStatusStore.update { it.copy(clientCount = count) }
            runCatching { listener?.onClientCountChanged(count) }
            true
        }

    private fun unregisterClient(socket: Socket, generation: Long, counted: Boolean) =
        synchronized(lifecycleLock) {
            clients.remove(socket)
            clientOutputs.remove(socket)
            if (counted && generation == serverGeneration) {
                val count = clientCount.updateAndGet { (it - 1).coerceAtLeast(0) }
                TvStatusStore.update { it.copy(clientCount = count) }
                runCatching { listener?.onClientCountChanged(count) }
            }
        }
}
