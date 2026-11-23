// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import com.hongguotv.core.BackupProgress
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.Detail
import com.hongguotv.core.PlaybackQuality
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.RequestScope
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Slider
import javafx.scene.image.ImageView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.util.Duration
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.javafx.videosurface.ImageViewVideoSurface
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ExecutorService

internal class PlayerWindow(
    owner: Stage,
    private val repository: ContentRepository,
    private val library: DesktopLibrary,
    executor: ExecutorService,
    private val libraryChanged: () -> Unit,
) : Closeable {
    private data class Episode(val detail: Detail, val index: Int) {
        val id get() = detail.episodes[index]
    }

    private class PlaybackSource(private val video: RemoteVideo) : Closeable {
        val media = VlcMedia(video.total, video::openRead)

        override fun close() {
            video.close()
            media.close()
        }
    }

    private val stage = Stage().apply {
        initOwner(owner)
        applyBrand()
        minWidth = 740.0
        minHeight = 460.0
    }
    private val request = LatestRequest(executor)
    private var episode: Episode? = null
    private var source: PlaybackSource? = null
    private var factory: MediaPlayerFactory? = null
    private var mediaPlayer: EmbeddedMediaPlayer? = null
    private var resumeAt = 0L
    private var pendingSeek = 0L
    private var changingEpisode = false
    private var ready = false
    private var completed = false

    private val mediaView = ImageView().apply { isPreserveRatio = true }
    private val display = StackPane(mediaView).apply { styleClass += "video" }
    private val status = label("", "player-message").apply { isWrapText = true }
    private val retry = button("重试", "primary") {
        episode?.let { load(it, resumeAt) }
    }
    private val overlay = VBox(11.0, status, retry).apply { alignment = Pos.CENTER; padding = Insets(24.0) }
    private val title = label("", "detail-title")
    private val playPause = button("暂停", "primary", ::togglePlayback)
    private val previous = button("上一集") { changeEpisode(-1) }
    private val next = button("下一集") { changeEpisode(1) }
    private val episodes = ComboBox<String>().apply {
        accessibleText = "选集"
        setOnAction {
            val current = episode ?: return@setOnAction
            val index = selectionModel.selectedIndex
            if (!changingEpisode && index in current.detail.episodes.indices && index != current.index) {
                show(current.detail, index, savedPosition(current.detail, index))
            }
        }
    }
    private val quality = ComboBox<String>().apply {
        items.addAll(PlaybackQuality.options.map { "${it}P" })
        value = "${library.index.settings.quality}P"
        accessibleText = "首选清晰度"
        setOnAction {
            val position = currentPosition() ?: resumeAt
            library.index.settings = library.index.settings.copy(quality = value.removeSuffix("P").toInt())
            saveLibrary()
            episode?.let { load(it, position) }
        }
    }
    private val speed = ComboBox<Double>().apply {
        items.addAll(0.75, 1.0, 1.25, 1.5, 2.0)
        value = library.index.settings.speed.toDouble().takeIf { it in items } ?: 1.0
        accessibleText = "播放速度"
        buttonCell = javafx.scene.control.ListCell<Double>().apply {
            itemProperty().addListener { _, _, rate -> text = rate?.let { "${it}×" }.orEmpty() }
        }
        setOnAction {
            mediaPlayer?.controls()?.setRate(value.toFloat())
            library.index.settings = library.index.settings.copy(speed = value.toFloat())
            saveLibrary()
        }
    }
    private val autoNext = CheckBox("自动连播").apply {
        isSelected = library.index.settings.autoNext
        setOnAction {
            library.index.settings = library.index.settings.copy(autoNext = isSelected)
            saveLibrary()
        }
    }
    private val time = label("0:00 / 0:00", "muted")
    private val volume = Slider(0.0, 100.0, 100.0).apply {
        prefWidth = 70.0
        accessibleText = "音量"
        valueProperty().addListener { _, _, value -> mediaPlayer?.audio()?.setVolume(value.toInt()) }
    }
    private val seek = Slider().apply {
        accessibleText = "播放进度"
        setOnMouseReleased { seekTo(value) }
        setOnKeyReleased { seekTo(value) }
    }
    private val checkpoint = Timeline(KeyFrame(Duration.seconds(5.0), { saveProgress() })).apply {
        cycleCount = Timeline.INDEFINITE
    }
    private val refresh = Timeline(KeyFrame(Duration.millis(250.0), { updatePosition() })).apply {
        cycleCount = Timeline.INDEFINITE
    }

    init {
        display.children += overlay
        mediaView.fitWidthProperty().bind(display.widthProperty())
        mediaView.fitHeightProperty().bind(display.heightProperty())
        val playbackBar = HBox(7.0, playPause, previous, episodes, next, speed, quality, autoNext).apply {
            alignment = Pos.CENTER_LEFT
        }
        val secondRow = HBox(8.0, time, spacer(), label("音量", "muted"), volume, button("全屏") {
            stage.isFullScreen = !stage.isFullScreen
        }).apply { alignment = Pos.CENTER_LEFT }
        val controls = VBox(7.0, seek, playbackBar, secondRow).apply {
            padding = Insets(7.0, 14.0, 12.0, 14.0)
            styleClass += "toolbar"
        }
        val header = HBox(title).apply { padding = Insets(11.0, 14.0, 11.0, 14.0); styleClass += "toolbar" }
        val layout = BorderPane(display, header, null, controls, null)
        stage.scene = desktopScene(layout, 900.0, 660.0)
        stage.setOnHidden { release() }
        stage.scene.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (event.code == KeyCode.F11) {
                stage.isFullScreen = !stage.isFullScreen
                event.consume()
            }
        }
    }

    fun show(detail: Detail, index: Int, position: Long) {
        if (index !in detail.episodes.indices) return
        load(Episode(detail, index), position)
        if (!stage.isShowing) stage.show()
        stage.toFront()
    }

    private fun load(current: Episode, position: Long) {
        closePlayback()
        episode = current
        resumeAt = position
        title.text = "${current.detail.series.title} · 第 ${current.index + 1} 集"
        stage.title = title.text
        changingEpisode = true
        episodes.items.setAll(current.detail.episodes.indices.map { "第 ${it + 1} 集" })
        episodes.selectionModel.select(current.index)
        changingEpisode = false
        previous.isDisable = current.index == 0
        next.isDisable = current.index == current.detail.episodes.lastIndex
        showMessage("正在准备播放…")
        val maxQuality = library.index.settings.quality
        request.start({ openSource(current.id, maxQuality) }) { result ->
            result.fold({ attach(it, position) }, { failed(it) })
        }
    }

    private fun openSource(id: String, maxQuality: Int): PlaybackSource {
        val ladder = repository.streamLadderWithCompatibility(id)
        val candidates = ladder.playbackOrder(maxQuality)
        var lastFailure: IOException? = null
        for (rendition in candidates) {
            RequestScope.ensureActive()
            val video = RemoteVideo(repository.http, rendition.toStreamInfo())
            var opened: PlaybackSource? = null
            try {
                video.prepare()
                RequestScope.ensureActive()
                opened = PlaybackSource(video)
                return opened
            } catch (error: IOException) {
                lastFailure = error
                RequestScope.ensureActive()
            } finally {
                if (opened == null) video.close()
            }
        }
        throw lastFailure ?: IOException("当前分集没有可播放的视频资源。")
    }

    private fun attach(opened: PlaybackSource, position: Long) {
        source = opened
        try {
            val engine = factory ?: BundledVlc.create().also { factory = it }
            val player = engine.mediaPlayers().newEmbeddedMediaPlayer()
            mediaPlayer = player
            pendingSeek = position
            player.videoSurface().set(ImageViewVideoSurface(mediaView))
            player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) = dispatch(player) {
                    if (!ready) {
                        ready = true
                        playPause.isDisable = false
                        player.audio().setVolume(volume.value.toInt())
                        player.controls().setRate(speed.value.toFloat())
                        overlay.isVisible = false
                        updatePosition()
                        refresh.play()
                        checkpoint.play()
                    }
                    playPause.text = "暂停"
                }

                override fun paused(mediaPlayer: MediaPlayer) = dispatch(player) {
                    playPause.text = "继续"
                    saveProgress()
                }

                override fun finished(mediaPlayer: MediaPlayer) = dispatch(player) {
                    completed = true
                    saveProgress()
                    playPause.text = "重播"
                    if (autoNext.isSelected && !next.isDisable) changeEpisode(1)
                }

                override fun error(mediaPlayer: MediaPlayer) = dispatch(player) { failed(null) }
            })
            if (!player.media().play(opened.media)) failed(null)
        } catch (error: Exception) {
            failed(error)
        } catch (error: LinkageError) {
            failed(error)
        }
    }

    private fun dispatch(player: MediaPlayer, action: () -> Unit) = Platform.runLater {
        if (mediaPlayer === player) action()
    }

    private fun updatePosition() {
        val player = mediaPlayer ?: return
        val length = player.status().length()
        val seekable = length > 0 && player.status().isSeekable
        seek.isDisable = !seekable || completed
        seek.max = length.coerceAtLeast(1).toDouble()
        if (seekable && pendingSeek > 0) {
            if (pendingSeek < length) player.controls().setTime(pendingSeek)
            pendingSeek = 0
        }
        val elapsed = if (completed && length > 0) length else player.status().time()
        if (!seek.isValueChanging && !seek.isPressed) seek.value = elapsed.coerceAtLeast(0).toDouble()
        time.text = "${clock(elapsed)} / ${clock(length)}"
    }

    private fun failed(error: Throwable?) {
        val position = currentPosition() ?: resumeAt
        closePlayback()
        resumeAt = position
        val message = if (error is IOException && error.message?.contains(Regex("[\\u4e00-\\u9fff]")) == true) {
            error.message.orEmpty()
        } else "播放失败。请重试或选择其他清晰度。"
        showMessage(message, canRetry = true)
    }

    private fun showMessage(message: String, canRetry: Boolean = false) {
        status.text = message
        retry.isManaged = canRetry
        retry.isVisible = canRetry
        overlay.isVisible = true
    }

    private fun changeEpisode(delta: Int) {
        val current = episode ?: return
        val index = current.index + delta
        if (index in current.detail.episodes.indices) show(current.detail, index, savedPosition(current.detail, index))
    }

    private fun savedPosition(detail: Detail, index: Int) = library.index.progress(detail.series.id)
        ?.takeIf { detail.episodes[index] == it.episodeId && !it.completed }?.position ?: 0

    private fun currentPosition() = mediaPlayer?.status()?.time()?.takeIf { ready && it >= 0 }

    private fun togglePlayback() {
        val player = mediaPlayer ?: return
        when {
            completed -> episode?.let { load(it, 0) }
            player.status().isPlaying -> player.controls().setPause(true)
            else -> player.controls().setPause(false)
        }
    }

    private fun seekTo(position: Double) {
        if (!ready || completed) return
        mediaPlayer?.controls()?.setTime(position.toLong())
    }

    private fun saveProgress() {
        val current = episode ?: return
        val duration = mediaPlayer?.status()?.length()?.coerceAtLeast(0) ?: 0L
        val position = if (completed && duration > 0) duration else currentPosition() ?: return
        if (position <= 0L && !completed) return
        val row = BackupProgress(current.detail.series, current.id, current.index,
            position, duration, completed, System.currentTimeMillis())
        if (library.index.save(row)) saveLibrary()
    }

    private fun saveLibrary() {
        runCatching { library.save() }.onFailure { time.text = "无法保存本机记录" }
    }

    private fun closePlayback() {
        request.cancel()
        checkpoint.stop()
        refresh.stop()
        saveProgress()
        ready = false
        completed = false
        val player = mediaPlayer
        mediaPlayer = null
        source?.close()
        source = null
        player?.controls()?.stop()
        player?.release()
        mediaView.image = null
        seek.value = 0.0
        seek.isDisable = true
        playPause.isDisable = true
        time.text = "0:00 / 0:00"
    }

    private fun release() {
        closePlayback()
        episode = null
        libraryChanged()
    }

    override fun close() {
        if (stage.isShowing) stage.hide() else closePlayback()
        factory?.release()
        factory = null
    }

    private fun spacer() = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }

    private fun clock(value: Long): String {
        val seconds = (value / 1000).coerceAtLeast(0)
        return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
}
