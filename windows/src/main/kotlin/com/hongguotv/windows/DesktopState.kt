// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import com.hongguotv.core.BackupData
import com.hongguotv.core.BackupSettings
import com.hongguotv.core.ContentType
import com.hongguotv.core.LibraryBackup
import com.hongguotv.core.LibraryIndex
import com.hongguotv.core.RequestScope
import javafx.application.Platform
import org.json.JSONObject
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

internal class DesktopLibrary {
    private val directory = Path.of(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "HongguoTV")
    private val file = directory.resolve("library.json")
    private val stored = runCatching { Files.readString(file) }.getOrDefault("")
    var rtxVsr = runCatching { JSONObject(stored).optBoolean("windowsRtxVsr") }.getOrDefault(false)
    val index = LibraryIndex(
        runCatching { LibraryBackup.decode(stored) }.getOrElse {
            BackupData(emptyList(), emptyList(), emptySet(), emptyList(), BackupSettings(type = ContentType.SHORT))
        },
    )

    fun save() {
        val snapshot = JSONObject(LibraryBackup.encode(index.snapshot())).put("windowsRtxVsr", rtxVsr).toString()
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "library-", ".tmp")
        try {
            Files.writeString(temp, snapshot)
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

/** Owns exactly one in-flight request. Results are delivered only to its current desktop view. */
internal class LatestRequest(private val executor: ExecutorService) {
    @Volatile private var scope: RequestScope? = null
    private var future: Future<*>? = null

    fun cancel() {
        scope?.cancel()
        future?.cancel(true)
        scope = null
        future = null
    }

    fun <T> start(work: () -> T, finished: (Result<T>) -> Unit) {
        cancel()
        val request = RequestScope()
        scope = request
        future = executor.submit {
            val result = runCatching { request.run(work) }
            if (scope !== request) {
                (result.getOrNull() as? AutoCloseable)?.close()
            } else {
                Platform.runLater {
                    if (scope === request) {
                        scope = null
                        future = null
                        finished(result)
                    } else {
                        (result.getOrNull() as? AutoCloseable)?.close()
                    }
                }
            }
        }
    }
}
