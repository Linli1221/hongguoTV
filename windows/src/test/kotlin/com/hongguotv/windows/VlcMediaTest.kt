// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class VlcMediaTest {
    @Test fun `sequential reads share bounded ranges and seeking reopens at the new offset`() {
        val rangeSize = 1024 * 1024
        val data = ByteArray(rangeSize + 20) { it.toByte() }
        val reads = mutableListOf<Pair<Long, Int>>()
        var closed = 0
        VlcMedia(data.size.toLong()) { offset, count ->
            reads += offset to count
            object : ByteArrayInputStream(data, offset.toInt(), count) {
                override fun close() { closed++ }
            }
        }.use { media ->
            val first = ByteArray(16)
            val second = ByteArray(16)
            assertEquals(data.size.toLong(), media.onGetSize())
            assertTrue(media.onOpen())
            assertEquals(16, media.onRead(first, first.size))
            assertArrayEquals(data.copyOfRange(0, 16), first)
            assertEquals(16, media.onRead(second, second.size))
            assertArrayEquals(data.copyOfRange(16, 32), second)
            assertEquals(listOf(0L to rangeSize), reads)

            assertTrue(media.onSeek((rangeSize - 6).toLong()))
            assertEquals(1, closed)
            assertEquals(16, media.onRead(first, first.size))
            assertArrayEquals(data.copyOfRange(rangeSize - 6, rangeSize + 10), first)
            assertEquals(10, media.onRead(second, second.size))
            assertEquals(-1, media.onRead(second, second.size))
            assertFalse(media.onSeek(data.size + 1L))
            assertEquals(listOf(0L to rangeSize, (rangeSize - 6).toLong() to 26), reads)
        }
        assertEquals(2, closed)
    }
}
