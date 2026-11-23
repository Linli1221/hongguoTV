// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import com.sun.jna.NativeLibrary
import uk.co.caprica.vlcj.binding.support.runtime.RuntimeUtil
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.media.callback.seekable.SeekableCallbackMedia
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

internal object BundledVlc {
    fun create(): MediaPlayerFactory {
        val jar = Path.of(BundledVlc::class.java.protectionDomain.codeSource.location.toURI())
        val directory = System.getProperty("hongguotv.vlc")?.let { Path.of(it) } ?: jar.resolveSibling("vlc")
        check(Files.isRegularFile(directory.resolve("libvlc.dll"))) { "未找到随包提供的 LibVLC。" }
        NativeLibrary.addSearchPath(RuntimeUtil.getLibVlcLibraryName(), directory.toString())
        return MediaPlayerFactory(null as NativeDiscovery?, "--ignore-config", "--no-video-title-show", "--avcodec-hw=none")
    }
}

/** Serves VLC's seekable media callbacks from bounded CDN ranges. */
internal class VlcMedia(
    private val size: Long,
    private val openRange: (Long, Int) -> InputStream,
) : SeekableCallbackMedia(64 * 1024), Closeable {
    private var input: InputStream? = null
    private var position = 0L
    private var remaining = 0

    public override fun onGetSize() = size

    @Synchronized
    public override fun onOpen(): Boolean {
        onClose()
        position = 0
        return size > 0
    }

    @Synchronized
    public override fun onRead(buffer: ByteArray, bufferSize: Int): Int {
        if (position == size) return -1
        if (bufferSize == 0) return 0
        if (remaining == 0) {
            onClose()
            val length = minOf(1024 * 1024L, size - position).toInt()
            input = openRange(position, length)
            remaining = length
        }
        val count = checkNotNull(input).read(buffer, 0, minOf(bufferSize, remaining))
        if (count < 0) throw IOException("视频响应提前结束。")
        position += count
        remaining -= count
        return count
    }

    @Synchronized
    public override fun onSeek(offset: Long): Boolean {
        if (offset !in 0..size) return false
        if (offset != position) {
            onClose()
            position = offset
        }
        return true
    }

    @Synchronized
    override fun onClose() {
        val previous = input
        remaining = 0
        input = null
        runCatching { previous?.close() }
    }

    override fun close() = onClose()
}
