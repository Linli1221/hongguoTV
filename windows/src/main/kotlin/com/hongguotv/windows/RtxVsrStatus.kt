// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

/** Interprets renderer diagnostics from the bundled LibVLC 3.0.24. */
internal class RtxVsrStatus {
    enum class State(val label: String, val fallback: Boolean = false) {
        STARTING("正在连接 Direct3D 11…"),
        WAITING("等待画面放大"),
        UNVERIFIED("超分请求已提交，显卡待确认"),
        REQUESTED("RTX VSR 请求已提交"),
        UNSUPPORTED("驱动未提供超分，已切回普通播放", true),
        OTHER_GPU("当前使用非 NVIDIA 显卡，已切回普通播放", true),
        OUTPUT_FAILED("Direct3D 11 不可用，已切回普通播放", true),
    }

    @Volatile var state = State.STARTING
        private set
    private var scalerReady = false
    private var upscaling = false
    private var sharedDecodeDevice = false
    private var nvidia: Boolean? = null

    @Synchronized
    fun update(module: String, message: String): State? {
        if (state.fallback) return null
        val failure = when {
            module == "direct3d11" && (message == "Super Resolution filter not supported" ||
                message.startsWith("Using Linear Sampler scaler") ||
                message.startsWith("Failed to set the NVIDIA video process stream extension")) -> State.UNSUPPORTED
            module == "main" && message.startsWith("using vout display module \"") &&
                message != "using vout display module \"direct3d11\"" -> State.OUTPUT_FAILED
            else -> null
        }
        when {
            module == "direct3d11" && message.startsWith("Using Super Resolution scaler") -> scalerReady = true
            module == "direct3d11" && message == "turning VSR ON" -> upscaling = true
            module == "direct3d11" && message == "turning VSR OFF" -> upscaling = false
            module == "d3d11va" && message == "Reusing Direct3D11 device" -> sharedDecodeDevice = true
            module == "avcodec" && sharedDecodeDevice && message.startsWith("Using D3D11VA (") &&
                message.contains(", vendor ") -> nvidia = message.contains(", vendor 10de(", ignoreCase = true)
        }
        val next = failure ?: when {
            nvidia == false -> State.OTHER_GPU
            upscaling && nvidia == true -> State.REQUESTED
            upscaling -> State.UNVERIFIED
            scalerReady -> State.WAITING
            else -> State.STARTING
        }
        if (next == state) return null
        state = next
        return next
    }
}
