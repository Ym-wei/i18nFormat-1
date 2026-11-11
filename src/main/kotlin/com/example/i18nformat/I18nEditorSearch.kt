package com.example.i18nformat

import com.intellij.find.EditorSearchSession
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.concurrent.Future
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent

/**
 * 让译文搜索条与原生查找栏同步显隐
 */
class I18nFindSyncStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.getService(I18nFindSyncService::class.java)
    }
}

@Service(Service.Level.PROJECT)
class I18nFindSyncService(private val project: Project) : Disposable {

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var bar: I18nEditorSearchSession? = null

    init {
        alarm.addRequest({ sync() }, 200)
    }
    private fun sync() {
        try {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val findSession = editor?.let { EditorSearchSession.get(it) }
            val current = bar

            if (editor == null || findSession == null) {
                current?.close()
                bar = null
            } else if (current == null || current.editor !== editor) {
                current?.close()
                bar = I18nEditorSearchSession(project, editor).also { it.attach(findSession.component) }
            } else if (!current.isAttached()) {
                // 原生查找栏关闭时头部被整体移除，下一轮重建
                current.close()
                bar = null
            }
        } finally {
            if (!project.isDisposed) {
                alarm.addRequest({ sync() }, 200)
            }
        }
    }

    override fun dispose() {
        bar?.close()
        bar = null
    }
}

private class I18nEditorSearchSession(
    private val project: Project,
    val editor: Editor
) : Disposable {

    // 用 EditorTextField 而不是普通 JTextField：它自带内嵌编辑器，
    // 方向键/删除键会被编辑器 action 作用到它自己的编辑器上，而不是外层代码编辑器
    private val field = EditorTextField(
        "", project, FileTypeManager.getInstance().getFileTypeByExtension("txt")
    ).apply {
        setOneLineMode(true)
        setFontInheritedFromLAF(true)
        setDisposedWith(this@I18nEditorSearchSession)
        border = null
        preferredSize = Dimension(JBUI.scale(400), JBUI.scale(30))
    }
    // 用插件 logo（预先栅格化为 18px PNG，SVG 在搜索条里渲染不可靠），@2x 供高分屏使用
    private val title = JBLabel(IconLoader.getIcon("/icons/i18n-logo.png", I18nEditorSearchSession::class.java)).apply {
        toolTipText = "I18n"
    }
    private val status = JBLabel("输入译文，如：登录").apply {
        foreground = JBColor.GRAY
    }
    private val hint = JBLabel("↑↓ 切换").apply {
        foreground = JBColor.GRAY
    }
    private var currentBox: RangeHighlighter? = null
    private var resizeTarget: JComponent? = null
    private var resizeListenerRef: ComponentAdapter? = null
    private var highlighters = mutableListOf<RangeHighlighter>()
    private var barPanel: JPanel? = null
    private var matches: List<IntRange> = emptyList()
    private var current = -1
    private var pending: Future<*>? = null

    /**
     * 把译文搜索条挂到原生查找栏下方（作为其 SOUTH 行）。
     * 注意：不能替换/包裹 editor.headerComponent，平台靠它反查查找会话。
     */
    fun attach(findBar: JComponent) {
        if (barPanel != null) return
        // 挂在原生查找栏自身（BorderLayout）的 SOUTH，高度由平台计算，不会被裁切
        val host = findBar as? JPanel ?: return
        if (host.layout !is BorderLayout) return

        val bar = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(2, 8)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
                isOpaque = false
                add(title)
                add(field)
                add(hint)
                add(status)
            }, BorderLayout.CENTER)
            add(ActionLink("全局搜索 (Ctrl+Alt+Shift+F)") {
                I18nGlobalSearchDialog(project).show()
            }.apply {
                border = JBUI.Borders.empty(0, 8)
            }, BorderLayout.EAST)
        }

        field.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) = scheduleSearch()
        })
        // 单行模式下上下键不会进文档，直接在内嵌编辑器组件上监听，用于切换匹配项
        field.addSettingsProvider { textEditor ->
            (textEditor as? EditorEx)?.setBackgroundColor(UIUtil.getPanelBackground())
            textEditor.contentComponent.addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_DOWN -> {
                            e.consume()
                            navigateNext()
                        }
                        KeyEvent.VK_UP -> {
                            e.consume()
                            navigatePrev()
                        }
                    }
                }
            })
        }

        host.add(bar, BorderLayout.SOUTH)
        // 宽度按查找栏宽度的 40% 计算，并跟随窗口缩放
        resizeFieldWidth(host)
        val resizeListener = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = resizeFieldWidth(host)
        }
        host.addComponentListener(resizeListener)
        resizeTarget = host
        resizeListenerRef = resizeListener
        revalidateUp(host)
        host.repaint()
        barPanel = bar
    }

    private fun resizeFieldWidth(host: JComponent) {
        val width = (host.width * 0.4f).toInt().coerceAtLeast(JBUI.scale(120))
        if (field.preferredSize.width != width) {
            field.preferredSize = Dimension(width, JBUI.scale(30))
            field.revalidate()
        }
    }

    // 编辑器头部高度由上层布局决定，只 revalidate 查找栏本身不够，需要逐级向上触发重排
    private fun revalidateUp(start: JComponent) {
        var c: JComponent? = start
        repeat(6) {
            c?.revalidate()
            c = c?.parent as? JComponent
        }
    }

    /**
     * 平台重建头部时会清空容器，我们的条也随之失去父级
     */
    fun isAttached(): Boolean = barPanel?.parent != null

    fun close() {
        val bar = barPanel
        val host = bar?.parent as? JPanel
        resizeListenerRef?.let { l -> resizeTarget?.removeComponentListener(l) }
        resizeTarget = null
        resizeListenerRef = null
        host?.remove(bar)
        if (host != null) revalidateUp(host)
        host?.repaint()
        barPanel = null
        clearHighlights()
        if (!Disposer.isDisposed(this)) Disposer.dispose(this)
    }

    override fun dispose() {
        barPanel = null
        clearHighlights()
    }

    private fun scheduleSearch() {
        pending?.cancel(false)
        val keyword = field.text.trim()
        if (keyword.isEmpty()) {
            ApplicationManager.getApplication().invokeLater({
                clearHighlights()
                status.text = "输入译文，如：登录"
            }, ModalityState.any())
            return
        }

        pending = AppExecutorUtil.getAppExecutorService().submit {
            val keys = project.getService(I18nTranslationService::class.java)
                .findEntries(keyword)
                .map { it.key }
                .distinct()
            val documentText = ReadAction.compute<String, RuntimeException> { editor.document.text }
            val ranges = collectRanges(documentText, keys)

            ApplicationManager.getApplication().invokeLater({
                if (field.text.trim() != keyword) return@invokeLater
                applyHighlights(ranges)
            }, ModalityState.any())
        }
    }

    /**
     * 在文件文本中查找 key 的字符串字面量写法（单引号/双引号）
     */
    private fun collectRanges(text: String, keys: List<String>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        for (key in keys) {
            for (quote in charArrayOf('\'', '"')) {
                val needle = "$quote$key$quote"
                var index = text.indexOf(needle)
                while (index >= 0) {
                    ranges += index until index + needle.length
                    index = text.indexOf(needle, index + needle.length)
                }
            }
        }
        return ranges.distinct().sortedBy { it.first }
    }

    private fun applyHighlights(ranges: List<IntRange>) {
        clearHighlights()
        matches = ranges
        current = -1

        if (ranges.isEmpty()) {
            status.text = "没有找到使用位置"
            return
        }

        val attributes = EditorColorsManager.getInstance().globalScheme
            .getAttributes(EditorColors.SEARCH_RESULT_ATTRIBUTES)

        ApplicationManager.getApplication().runWriteAction {
            val markup = editor.markupModel
            for (range in ranges) {
                highlighters += markup.addRangeHighlighter(
                    range.first,
                    range.last + 1,
                    HighlighterLayer.SELECTION - 1,
                    attributes,
                    HighlighterTargetArea.EXACT_RANGE
                )
            }
        }

        status.text = "共 ${ranges.size} 处"
        navigateNext()
    }

    private fun navigateNext() {
        if (matches.isEmpty()) return
        jumpTo((current + 1) % matches.size)
    }

    private fun navigatePrev() {
        if (matches.isEmpty()) return
        jumpTo((current - 1 + matches.size) % matches.size)
    }

    private fun jumpTo(index: Int) {
        current = index
        val range = matches[current]
        editor.caretModel.moveToOffset(range.first)
        editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
        markCurrent(range)
        status.text = "第 ${current + 1}/${matches.size} 处"
    }

    // 给当前选中的匹配加一个描边框，便于和其余匹配区分
    private fun markCurrent(range: IntRange) {
        val markup = editor.markupModel
        ApplicationManager.getApplication().runWriteAction {
            currentBox?.let { markup.removeHighlighter(it) }
            currentBox = markup.addRangeHighlighter(
                range.first,
                range.last + 1,
                HighlighterLayer.SELECTION,
                boxAttributes(),
                HighlighterTargetArea.EXACT_RANGE
            )
        }
    }

    // 当前项：直接使用主题里“编辑器选中”的配色（背景/前景），和原生选中效果一致
    private fun boxAttributes(): TextAttributes {
        val scheme = EditorColorsManager.getInstance().globalScheme
        return TextAttributes().apply {
            backgroundColor = scheme.getColor(EditorColors.SELECTION_BACKGROUND_COLOR)
            foregroundColor = scheme.getColor(EditorColors.SELECTION_FOREGROUND_COLOR)
        }
    }

    private fun clearHighlights() {
        if (highlighters.isEmpty() && matches.isEmpty() && currentBox == null) return
        ApplicationManager.getApplication().runWriteAction {
            highlighters.forEach { editor.markupModel.removeHighlighter(it) }
            currentBox?.let { editor.markupModel.removeHighlighter(it) }
        }
        highlighters.clear()
        currentBox = null
        matches = emptyList()
        current = -1
    }
}
