package com.android.portguard.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class RootCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0
    val combinedOutput: String
        get() = listOf(stdout.trim(), stderr.trim()).filter { it.isNotBlank() }.joinToString("\n")
}

class RootShell(
    private val timeoutMs: Long = 20_000L,
) {
    suspend fun testRoot(): Boolean = withContext(Dispatchers.IO) {
        val result = exec("id")
        result.isSuccess && result.stdout.contains("uid=0")
    }

    suspend fun exec(command: String): RootCommandResult = withContext(Dispatchers.IO) {
        SharedRootSession.exec(command = command, timeoutMs = timeoutMs)
    }

    suspend fun commandExists(binary: String): Boolean {
        val escaped = shellQuote(binary)
        return exec("command -v $escaped >/dev/null 2>&1").isSuccess
    }

    suspend fun fileExists(path: String): Boolean {
        val escaped = shellQuote(path)
        return exec("test -e $escaped").isSuccess
    }

    suspend fun mkdirs(path: String): Boolean {
        val escaped = shellQuote(path)
        return exec("mkdir -p $escaped").isSuccess
    }

    suspend fun readText(path: String): String? {
        val escaped = shellQuote(path)
        val result = exec("cat $escaped")
        return if (result.isSuccess) result.stdout else null
    }

    suspend fun writeTextAtomic(path: String, content: String): Boolean {
        val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isNotBlank()) {
            mkdirs(parent)
        }
        val tmp = "$path.tmp.${UUID.randomUUID()}"
        val tmpEscaped = shellQuote(tmp)
        val pathEscaped = shellQuote(path)
        val script = buildString {
            append("cat > $tmpEscaped <<'__PG_EOF__'\n")
            append(content)
            if (!content.endsWith("\n")) append('\n')
            append("__PG_EOF__\n")
            append("mv -f $tmpEscaped $pathEscaped\n")
        }
        return exec(script).isSuccess
    }

    fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    companion object {
        fun closeSharedSession() {
            SharedRootSession.close()
        }
    }

    private object SharedRootSession {
        private const val POLL_INTERVAL_MS = 10L
        private val lock = Any()
        private val commandCounter = AtomicLong(0L)

        @Volatile
        private var session: Session? = null

        fun exec(command: String, timeoutMs: Long): RootCommandResult = synchronized(lock) {
            val activeSession = try {
                ensureSessionLocked()
            } catch (t: Throwable) {
                return RootCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = t.message ?: "Не удалось открыть root-сессию",
                )
            }

            try {
                runCommandLocked(activeSession, command, timeoutMs)
            } catch (t: Throwable) {
                closeLocked()
                RootCommandResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = t.message ?: "Ошибка выполнения root-команды",
                )
            }
        }

        fun close() = synchronized(lock) {
            closeLocked()
        }

        private fun ensureSessionLocked(): Session {
            val current = session
            if (current != null && current.process.isAlive) return current

            closeLocked()

            val process = ProcessBuilder("su")
                .redirectErrorStream(true)
                .start()

            return Session(
                process = process,
                stdin = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8)),
                stdout = BufferedInputStream(process.inputStream),
            ).also { session = it }
        }

        private fun runCommandLocked(session: Session, command: String, timeoutMs: Long): RootCommandResult {
            val commandId = commandCounter.incrementAndGet()
            val markerToken = "__PG_CMD_DONE_${commandId}__:"
            val markerPrefixBytes = byteArrayOf(0) + markerToken.toByteArray(StandardCharsets.UTF_8)
            val markerEndByte = 0.toByte()
            val payload = ByteArrayOutputStream()
            val wrappedCommand = buildCommand(command = command, markerToken = markerToken, commandId = commandId)

            session.stdin.write(wrappedCommand)
            session.stdin.flush()

            val deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < deadlineNs) {
                drainAvailableOutput(session.stdout, payload)

                val bytes = payload.toByteArray()
                val markerIndex = bytes.indexOfSubarray(markerPrefixBytes)
                if (markerIndex >= 0) {
                    val codeStart = markerIndex + markerPrefixBytes.size
                    val codeEnd = bytes.indexOfByte(markerEndByte, startIndex = codeStart)
                    if (codeEnd >= 0) {
                        val exitCode = bytes.decodeExitCode(start = codeStart, endExclusive = codeEnd)
                        val stdout = bytes.copyOfRange(0, markerIndex)
                            .toString(StandardCharsets.UTF_8)
                            .trimEnd('\n', '\r')
                        return RootCommandResult(
                            exitCode = exitCode,
                            stdout = stdout,
                            stderr = "",
                        )
                    }
                }

                if (!session.process.isAlive) {
                    drainAvailableOutput(session.stdout, payload)
                    val partial = payload.toByteArray().toString(StandardCharsets.UTF_8).trim()
                    throw IllegalStateException(
                        partial.ifBlank { "Root-сессия завершилась во время выполнения команды" }
                    )
                }

                Thread.sleep(POLL_INTERVAL_MS)
            }

            closeLocked()
            return RootCommandResult(
                exitCode = -1,
                stdout = payload.toByteArray().toString(StandardCharsets.UTF_8).trim(),
                stderr = "Command timed out after ${timeoutMs} ms",
            )
        }

        private fun buildCommand(command: String, markerToken: String, commandId: Long): String {
            val escapedMarkerToken = markerToken
                .replace("\\", "\\\\")
                .replace("'", "'\\''")

            return buildString {
                append("__pg_cmd_status_${commandId}=0\n")
                append("{\n")
                append(command)
                if (!command.endsWith("\n")) append('\n')
                append("}\n")
                append("__pg_cmd_status_${commandId}=$?\n")
                append("printf '\\000")
                append(escapedMarkerToken)
                append("%s\\000' \"\$__pg_cmd_status_${commandId}\"\n")
            }
        }

        private fun drainAvailableOutput(input: BufferedInputStream, payload: ByteArrayOutputStream) {
            while (true) {
                val available = input.available()
                if (available <= 0) break
                val buffer = ByteArray(minOf(available, 8_192))
                val read = input.read(buffer)
                if (read <= 0) break
                payload.write(buffer, 0, read)
            }
        }

        private fun closeLocked() {
            val current = session ?: return
            runCatching {
                current.stdin.write("exit\n")
                current.stdin.flush()
            }
            runCatching { current.stdin.close() }
            runCatching { current.stdout.close() }
            runCatching { current.process.destroy() }
            runCatching {
                if (current.process.isAlive) current.process.destroyForcibly()
            }
            session = null
        }

        private data class Session(
            val process: Process,
            val stdin: BufferedWriter,
            val stdout: BufferedInputStream,
        )
    }
}

private fun ByteArray.indexOfSubarray(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    outer@ for (start in 0..(size - needle.size)) {
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) continue@outer
        }
        return start
    }
    return -1
}

private fun ByteArray.indexOfByte(target: Byte, startIndex: Int): Int {
    for (index in startIndex until size) {
        if (this[index] == target) return index
    }
    return -1
}

private fun ByteArray.decodeExitCode(start: Int, endExclusive: Int): Int {
    return copyOfRange(start, endExclusive)
        .toString(StandardCharsets.UTF_8)
        .trim()
        .toIntOrNull()
        ?: -1
}
