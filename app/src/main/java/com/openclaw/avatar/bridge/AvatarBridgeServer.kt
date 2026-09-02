package com.openclaw.avatar.bridge

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class BridgeRequest(val method: String, val path: String, val body: String)
data class BridgeResponse(val status: Int, val json: String)

class AvatarBridgeServer(
    private val onRequest: (BridgeRequest) -> BridgeResponse
) {
    companion object {
        private const val TAG = "AvatarBridge"
        private val PORT_CANDIDATES = intArrayOf(8791, 8792, 8793, 8794, 8795)
    }

    var boundPort: Int = -1; private set

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "avatar-bridge-worker").apply { isDaemon = true }
    }

    fun start(): Int {
        if (running) return boundPort
        val loopback = InetAddress.getByName("127.0.0.1")
        for (port in PORT_CANDIDATES) {
            try {
                val s = ServerSocket(port, 4, loopback)
                s.reuseAddress = true
                serverSocket = s
                boundPort = port
                running = true
                break
            } catch (e: Exception) {
                Log.w(TAG, "bind failed on $port: ${e.message}")
            }
        }
        if (!running) {
            Log.e(TAG, "unable to bind any port")
            return -1
        }

        acceptThread = Thread({
            val srv = serverSocket ?: return@Thread
            while (running) {
                try {
                    val client = srv.accept()
                    workers.execute { handleClient(client) }
                } catch (_: Exception) {
                    if (running) Thread.sleep(50)
                }
            }
        }, "avatar-bridge-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "bridge server started on 127.0.0.1:$boundPort")
        return boundPort
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        try { acceptThread?.join(500) } catch (_: Exception) {}
        acceptThread = null
        workers.shutdown()
        try { workers.awaitTermination(500, TimeUnit.MILLISECONDS) } catch (_: Exception) {}
    }

    private fun handleClient(socket: Socket) {
        try {
            if (!socket.inetAddress.isLoopbackAddress) {
                socket.close(); return
            }
            socket.soTimeout = 3000
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) { writeError(socket.getOutputStream(), 400, "bad request"); return }
            val method = parts[0]
            val path = parts[1]

            var contentLength = 0
            while (true) {
                val header = input.readLine() ?: break
                if (header.isEmpty()) break
                val h = header.lowercase()
                if (h.startsWith("content-length:")) {
                    contentLength = h.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val n = input.read(buf, readTotal, contentLength - readTotal)
                    if (n < 0) break
                    readTotal += n
                }
                String(buf, 0, readTotal)
            } else ""

            val resp = try {
                onRequest(BridgeRequest(method, path, body))
            } catch (e: Exception) {
                BridgeResponse(500, """{"error":"${e.message?.replace("\"", "'")}"}""")
            }
            writeResponse(socket.getOutputStream(), resp.status, resp.json)
        } catch (_: Exception) {
            // swallow, connection likely closed
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun writeResponse(out: OutputStream, status: Int, json: String) {
        val statusText = statusText(status)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status $statusText\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }

    private fun writeError(out: OutputStream, status: Int, msg: String) {
        writeResponse(out, status, """{"error":"$msg"}""")
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }
}
