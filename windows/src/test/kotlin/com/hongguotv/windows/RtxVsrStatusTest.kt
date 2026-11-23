// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import com.hongguotv.windows.RtxVsrStatus.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtxVsrStatusTest {
    @Test fun `renderer request and shared NVIDIA device can be reported in either order`() {
        val status = RtxVsrStatus()
        assertEquals(State.WAITING, status.update("direct3d11", "Using Super Resolution scaler with B8G8R8A8 output"))
        assertEquals(State.UNVERIFIED, status.update("direct3d11", "turning VSR ON"))
        assertNull(status.update("d3d11va", "Reusing Direct3D11 device"))
        assertEquals(State.REQUESTED, status.update("avcodec", "Using D3D11VA (GeForce RTX, vendor 10de(NVIDIA), device 2520) for hardware decoding"))
        assertEquals(State.WAITING, status.update("direct3d11", "turning VSR OFF"))
        assertEquals(State.REQUESTED, status.update("direct3d11", "turning VSR ON"))
    }

    @Test fun `rejected driver requests and other adapters switch to ordinary playback`() {
        val rejected = RtxVsrStatus()
        assertEquals(State.UNSUPPORTED, rejected.update("direct3d11", "Super Resolution filter not supported"))
        assertNull(rejected.update("direct3d11", "turning VSR ON"))

        val nonNvidia = RtxVsrStatus()
        val intel = "Using D3D11VA (Intel UHD Graphics, vendor 8086(Intel), device 0000) for hardware decoding"
        assertNull(nonNvidia.update("avcodec", intel))
        assertNull(nonNvidia.update("d3d11va", "Reusing Direct3D11 device"))
        assertEquals(State.OTHER_GPU, nonNvidia.update("avcodec", intel))
    }

    @Test fun `using a different video renderer requests a fallback`() {
        val status = RtxVsrStatus()
        assertNull(status.update("main", "using vout display module \"direct3d11\""))
        assertEquals(State.OUTPUT_FAILED, status.update("main", "using vout display module \"direct3d9\""))
    }
}
