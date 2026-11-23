// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.windows

import com.hongguotv.core.CatalogPage
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.ContentType
import com.hongguotv.core.Detail
import com.hongguotv.core.RankBoard
import com.hongguotv.core.ResumePlayback
import com.hongguotv.core.SearchSessionExpiredException
import com.hongguotv.core.Series
import javafx.geometry.Insets
import javafx.geometry.Orientation
import javafx.geometry.Pos
import javafx.scene.AccessibleRole
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.BorderPane
import javafx.scene.layout.FlowPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.TilePane
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.util.StringConverter
import java.net.URI
import java.util.concurrent.Executors

internal class CatalogWindow(private val stage: Stage, offline: Boolean) {
    private enum class View(val label: String) {
        HOME("推荐"), RANK("排行榜"), SEARCH("搜索结果"), FAVORITES("本机收藏"), HISTORY("观看历史"),
    }

    private val repository = ContentRepository()
    private val library = DesktopLibrary()
    private val executor = Executors.newFixedThreadPool(5) { task ->
        Thread(task, "HongguoTV request").apply { isDaemon = true }
    }
    private val request = LatestRequest(executor)
    private val detailRequest = LatestRequest(executor)
    private val player = PlayerWindow(stage, repository, library, executor, ::libraryChanged)
    private var view = View.HOME
    private var query = ""
    private var page = 0
    private var selected: Series? = null
    private var loadedDetail: Detail? = null
    private val seen = mutableSetOf<String>()

    private val search = TextField().apply {
        promptText = "搜索剧名"
        accessibleText = "搜索剧名"
        setOnAction { search() }
        prefColumnCount = 17
    }
    private val heading = label("推荐", "page-title")
    private val status = label("", "muted").apply { isWrapText = true }
    private val shelf = TilePane(Orientation.HORIZONTAL, 16.0, 18.0).apply {
        id = "catalog"
        prefTileWidth = 148.0
        tileAlignment = Pos.TOP_CENTER
    }
    private val more = button("加载更多") { loadNext() }
    private val contentType = choices(ContentType.entries, library.index.settings.type) { it.label }.apply {
        accessibleText = "内容类型"
        setOnAction {
            library.index.settings = library.index.settings.copy(type = value)
            saveLibrary()
            navigate(view)
        }
    }
    private val rankBoard = choices(RankBoard.entries, RankBoard.ALL) { it.label }.apply {
        accessibleText = "榜单类型"
        setOnAction { navigate(View.RANK) }
    }
    private val detail = VBox(14.0).apply {
        padding = Insets(22.0)
        styleClass += "details"
    }
    private val catalogScroll = ScrollPane(VBox(14.0, shelf, more).apply {
        padding = Insets(4.0, 22.0, 22.0, 22.0)
    }).apply {
        isFitToWidth = true
        hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
    }
    private val navigation = listOf(View.HOME, View.RANK, View.FAVORITES, View.HISTORY).associateWith { destination ->
        button(destination.label, "navigation-button") { navigate(destination) }.apply { maxWidth = Double.MAX_VALUE }
    }

    init {
        val logo = ImageView(Image(requireNotNull(javaClass.getResourceAsStream("/brand/logo-pixel.png")))).apply {
            fitWidth = 34.0
            fitHeight = 34.0
            isPreserveRatio = true
        }
        val brand = HBox(9.0, logo, label("红果 TV", "brand-name")).apply { alignment = Pos.CENTER_LEFT }
        val header = HBox(10.0, brand, spacer(), search, button("搜索", "primary") { search() }).apply {
            padding = Insets(12.0, 22.0, 12.0, 18.0)
            alignment = Pos.CENTER_LEFT
            styleClass += "toolbar"
        }
        val rail = VBox(7.0).apply {
            padding = Insets(19.0, 12.0, 16.0, 12.0)
            prefWidth = 126.0
            children.addAll(navigation.values)
            styleClass += "navigation"
        }
        val sectionHeader = HBox(10.0, heading, spacer(), contentType, rankBoard).apply { alignment = Pos.CENTER_LEFT }
        val section = VBox(7.0, sectionHeader, status).apply { padding = Insets(18.0, 22.0, 6.0, 22.0) }
        val detailsScroll = ScrollPane(detail).apply {
            prefWidth = 294.0
            minWidth = 252.0
            isFitToWidth = true
            isFitToHeight = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            styleClass += "details-scroll"
        }
        val center = VBox(8.0, section, catalogScroll).apply { VBox.setVgrow(catalogScroll, Priority.ALWAYS) }
        val layout = BorderPane(center, header, detailsScroll, null, rail)
        stage.title = "红果 TV"
        stage.minWidth = 880.0
        stage.minHeight = 600.0
        stage.applyBrand()
        stage.scene = desktopScene(layout, 1160.0, 780.0)
        detail.children.setAll(label("从左侧选择一部剧查看分集", "muted"))
        stage.show()
        if (offline) {
            setVisible(more, false)
            showRows(listOf(Series("1", "播放测试", badge = "示例")))
        } else navigate(View.HOME)
    }

    private fun search() {
        val text = search.text.trim()
        if (text.isEmpty()) return
        query = text.take(80)
        search.text = query
        navigate(View.SEARCH)
    }

    private fun navigate(next: View) {
        request.cancel()
        view = next
        page = 0
        seen.clear()
        shelf.children.clear()
        catalogScroll.vvalue = 0.0
        heading.text = if (next == View.SEARCH) "搜索 · $query" else next.label
        navigation.forEach { (route, item) ->
            item.styleClass.remove("selected")
            if (route == next) item.styleClass += "selected"
        }
        setVisible(rankBoard, next == View.RANK)
        setVisible(contentType, next == View.HOME || next == View.SEARCH)
        setVisible(more, false)
        if (next == View.FAVORITES || next == View.HISTORY) showLibrary() else loadNext()
    }

    private fun loadNext() {
        val destination = view
        val keyword = query
        val type = contentType.value
        val board = rankBoard.value
        val nextPage = page + 1
        val load: () -> CatalogPage = when (destination) {
            View.HOME -> { { repository.home(nextPage, type) } }
            View.RANK -> { { repository.rankPage(board, nextPage) } }
            View.SEARCH -> { { repository.search(keyword, nextPage, type) } }
            else -> return
        }
        status.text = "正在加载…"
        more.isDisable = true
        request.start(load) { result ->
            more.isDisable = false
            result.fold({ rows ->
                page = nextPage
                showRows(rows.items)
                status.text = if (seen.isEmpty()) "没有找到剧集。" else "已显示 ${seen.size} 部"
                more.text = "加载更多"
                setVisible(more, rows.hasMore)
            }, {
                val expired = it is SearchSessionExpiredException
                status.text = if (expired) "搜索已过期，将从第 1 页重试。" else "加载失败，请稍后重试。"
                if (expired) {
                    page = 0
                    seen.clear()
                    shelf.children.clear()
                }
                more.text = "重试"
                setVisible(more, true)
            })
        }
    }

    private fun showLibrary() {
        shelf.children.clear()
        seen.clear()
        val rows = if (view == View.FAVORITES) library.index.favorites() else library.index.history().map { it.series }
        showRows(rows)
        status.text = when {
            rows.isNotEmpty() -> "共 ${rows.size} 部"
            view == View.FAVORITES -> "打开剧集详情后即可添加收藏。"
            else -> "播放过的剧集会显示在这里。"
        }
    }

    private fun showRows(rows: List<Series>) {
        for (row in rows) {
            if (!seen.add(row.id)) continue
            val poster = StackPane(label(row.title.take(1), "poster-letter")).apply {
                styleClass += "poster"
                prefWidth = 148.0
                prefHeight = 170.0
            }
            cover(row.cover, 148.0, 170.0)?.let { poster.children += it }
            val meta = if (view == View.HISTORY) {
                library.index.progress(row.id)?.let { "第 ${it.episodeIndex + 1} 集" }.orEmpty()
            } else row.badge.ifBlank { row.tags }
            val text = VBox(4.0, label(row.title, "poster-title").apply { isWrapText = true; maxHeight = 42.0 },
                label(meta, "muted").apply { maxWidth = 138.0 }).apply { padding = Insets(8.0, 5.0, 10.0, 5.0) }
            val card = Button().apply {
                styleClass += "series-card"
                graphic = VBox(poster, text)
                prefWidth = 148.0
                accessibleRole = AccessibleRole.BUTTON
                accessibleText = row.title
                setOnAction { open(row) }
            }
            shelf.children += card
        }
    }

    private fun open(series: Series) {
        selected = series
        loadedDetail = null
        renderDetails(series, null)
        detailRequest.start({ repository.detail(series.id) }) { result ->
            loadedDetail = result.getOrNull()
            renderDetails(loadedDetail?.series ?: series, loadedDetail, failed = result.isFailure)
        }
    }

    private fun renderDetails(series: Series, content: Detail?, failed: Boolean = false) {
        val title = label(series.title, "detail-title").apply { isWrapText = true }
        detail.children.setAll(title)
        cover(series.cover, 210.0, 172.0)?.let { picture ->
            detail.children += StackPane(picture).apply { styleClass += "detail-poster" }
        }
        listOf(series.tags, series.description).filter { it.isNotBlank() }.forEach {
            detail.children += label(it, "muted").apply { isWrapText = true; maxHeight = 114.0 }
        }
        val favorite = button(if (library.index.favorite(series.id)) "取消收藏" else "收藏到本机") {
            library.index.toggle(series)
            saveLibrary()
            if (view == View.FAVORITES) showLibrary()
            renderDetails(series, content, failed)
        }
        if (content == null) {
            detail.children += favorite
            detail.children += if (failed) button("重新加载详情") { open(series) } else label("正在加载分集…", "muted")
            return
        }
        if (content.episodes.isEmpty()) {
            detail.children.addAll(favorite, label("暂无可播放分集。", "muted"))
            return
        }
        val progress = library.index.progress(series.id)
        val resume = ResumePlayback.target(content.episodes, progress?.episodeId, progress?.position ?: 0, progress?.completed == true)
        val play = button(if (progress == null) "播放第 1 集" else "续播第 ${resume.index + 1} 集", "primary") {
            player.show(content, resume.index, resume.position)
        }
        val episodes = FlowPane(7.0, 7.0)
        content.episodes.forEachIndexed { index, episodeId ->
            episodes.children += button("${index + 1}", "episode") {
                val position = library.index.progress(series.id)
                    ?.takeIf { it.episodeId == episodeId && !it.completed }?.position ?: 0
                player.show(content, index, position)
            }.apply { accessibleText = "第 ${index + 1} 集" }
        }
        detail.children.addAll(HBox(7.0, play, favorite), label("分集 · ${content.episodes.size}", "detail-section"), episodes)
    }

    private fun libraryChanged() {
        if (view == View.HISTORY) showLibrary()
        val series = selected ?: return
        loadedDetail?.let { renderDetails(it.series, it) }
    }

    private fun saveLibrary() {
        runCatching { library.save() }.onFailure { status.text = "无法保存本机记录。" }
    }

    fun close() {
        request.cancel()
        detailRequest.cancel()
        player.close()
        executor.shutdownNow()
    }

    private fun cover(address: String, width: Double, height: Double): ImageView? {
        val uri = runCatching { URI(address) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        val image = runCatching { Image(uri.toASCIIString(), width * 2, height * 2, true, true, true) }.getOrNull()
            ?: return null
        return ImageView(image).apply {
            fitWidth = width
            fitHeight = height
            isPreserveRatio = true
            isSmooth = true
        }
    }

    private fun setVisible(node: Node, value: Boolean) {
        node.isManaged = value
        node.isVisible = value
    }

    private fun <T> choices(items: List<T>, initial: T, display: (T) -> String) = ComboBox<T>().apply {
        this.items.addAll(items)
        converter = object : StringConverter<T>() {
            override fun toString(value: T?) = value?.let(display).orEmpty()
            override fun fromString(text: String) = items.firstOrNull { display(it) == text }
        }
        value = initial
    }

    private fun spacer() = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
}
