// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.image.Image
import javafx.stage.Stage
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    Application.launch(DesktopApplication::class.java, *args)
}

class DesktopApplication : Application() {
    private var catalog: CatalogWindow? = null

    override fun start(stage: Stage) {
        val smokeResult = parameters.named["smoke-test"]
        // The same launcher and JavaFX scene are used by the packaged CI smoke test.
        catalog = CatalogWindow(stage, offline = smokeResult != null)
        if (smokeResult != null) {
            Platform.runLater {
                try {
                    check(stage.isShowing && stage.scene.lookup("#catalog") != null)
                    val vlc = BundledVlc.create()
                    try {
                        Files.writeString(Path.of(smokeResult), "HongguoTV ready (LibVLC ${vlc.application().version()})\n")
                    } finally {
                        vlc.release()
                    }
                } finally {
                    Platform.exit()
                }
            }
        }
    }

    override fun stop() {
        catalog?.close()
    }
}

internal fun desktopScene(root: Parent, width: Double, height: Double) =
    Scene(root, width, height).apply {
        stylesheets += requireNotNull(DesktopApplication::class.java.getResource("/desktop.css")).toExternalForm()
    }

internal fun Stage.applyBrand() {
    icons += Image(requireNotNull(DesktopApplication::class.java.getResourceAsStream("/brand/logo-pixel.png")))
}

internal fun button(text: String, style: String? = null, action: () -> Unit) = Button(text).apply {
    style?.let { styleClass += it }
    setOnAction { action() }
}

internal fun label(text: String, style: String? = null) = Label(text).apply {
    style?.let { styleClass += it }
}
