// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import com.sun.javafx.stage.WindowHelper
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinUser
import javafx.beans.InvalidationListener
import javafx.scene.layout.Region
import javafx.stage.Stage
import java.io.Closeable
import kotlin.math.roundToInt

/** Owns the child HWND on which LibVLC creates its Direct3D swap chain. */
internal class Direct3dVideoSurface(private val stage: Stage, private val region: Region) : Closeable {
    private val user32 = User32.INSTANCE
    // The Windows JavaFX backend exposes its client HWND through the stage peer.
    private val parent = WindowHelper.getPeer(stage)?.rawHandle?.takeIf { it != 0L }
        ?: error("播放窗口尚未就绪。")
    private val window = checkNotNull(user32.CreateWindowEx(
        0, "STATIC", null,
        WinUser.WS_CHILD or WinUser.WS_CLIPSIBLINGS or WinUser.WS_CLIPCHILDREN or WinUser.WS_DISABLED,
        0, 0, 1, 1, HWND(Pointer.createConstant(parent)), null, null, null,
    )) { "无法创建 Direct3D 11 视频窗口。" }
    val handle: Long get() = Pointer.nativeValue(window.pointer)

    private val geometry = listOf(
        region.layoutBoundsProperty(), region.localToSceneTransformProperty(),
        stage.outputScaleXProperty(), stage.outputScaleYProperty(),
    )
    private val resize = InvalidationListener { updateBounds() }

    init {
        geometry.forEach { it.addListener(resize) }
        updateBounds()
    }

    fun show() {
        user32.ShowWindow(window, WinUser.SW_SHOWNOACTIVATE)
    }

    private fun updateBounds() {
        val bounds = region.localToScene(region.layoutBounds)
        val left = (bounds.minX * stage.outputScaleX).roundToInt()
        val top = (bounds.minY * stage.outputScaleY).roundToInt()
        val right = (bounds.maxX * stage.outputScaleX).roundToInt()
        val bottom = (bounds.maxY * stage.outputScaleY).roundToInt()
        user32.MoveWindow(window, left, top, (right - left).coerceAtLeast(1), (bottom - top).coerceAtLeast(1), true)
    }

    override fun close() {
        geometry.forEach { it.removeListener(resize) }
        user32.DestroyWindow(window)
    }
}
