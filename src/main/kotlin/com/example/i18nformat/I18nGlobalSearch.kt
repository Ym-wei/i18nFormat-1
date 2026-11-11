package com.example.i18nformat

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ContentIterator
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.Future
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * 一处引用：哪个文件的第几行引用了某个 key
 */
data class I18nUsage(
    val file: VirtualFile,
    val offset: Int,
    val line: Int,
    val key: String,
    val value: String
)

internal const val I18N_MAX_RESULTS = 300
private const val MAX_FILE_SIZE = 2L * 1024 * 1024
private val CODE_EXTENSIONS = setOf("vue", "ts", "js", "jsx", "tsx", "html")
private val IGNORED_DIRS = setOf(
    "node_modules", "unpackage", "dist", "build", "out", "coverage",
    ".git", ".idea", ".gradle", ".hbuilderx"
)
private val TextCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

// 结果树的配色：深浅主题各一套
private val ACCENT_COLOR = JBColor(0x2F6FA8, 0x7FB2E5)
private val COUNT_ATTRIBUTES = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, ACCENT_COLOR)
private val PATH_ATTRIBUTES = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0x8A8F98, 0x9AA0A6))
private val MATCH_ATTRIBUTES = SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor(0xC2701A, 0xE0A15E))
private val KEY_ATTRIBUTES = SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(0x7A6BA8, 0xB3A6E6))

/**
 * 输入译文 -> 在 zh.json 中匹配 key -> 扫描工程内代码文件，找出这些 key 的引用位置
 */
internal fun collectI18nUsages(project: Project, keyword: String, cancelled: () -> Boolean): List<I18nUsage> {
    val entries = project.service<I18nTranslationService>().findEntries(keyword)
    if (entries.isEmpty()) return emptyList()

    val uniqueEntries = entries.distinctBy { it.key }
    val result = mutableListOf<I18nUsage>()
    for (file in collectCandidateFiles(project)) {
        if (result.size >= I18N_MAX_RESULTS || cancelled()) break
        val text = loadText(file) ?: continue
        val lineStarts = lineStartsOf(text)
        scanFile(file, text, lineStarts, uniqueEntries, result)
    }
    return result.sortedWith(compareBy({ it.file.name }, { it.line }))
}

// 只遍历工程内、扩展名匹配、体积合理的文件，跳过依赖/产物目录
private fun collectCandidateFiles(project: Project): List<VirtualFile> {
    val files = mutableListOf<VirtualFile>()
    ReadAction.compute<Unit, RuntimeException> {
        ProjectFileIndex.getInstance(project).iterateContent(ContentIterator { file ->
            if (file.isDirectory || file.extension !in CODE_EXTENSIONS) return@ContentIterator true
            if (file.length > MAX_FILE_SIZE || isIgnoredPath(file.path)) return@ContentIterator true
            files += file
            true
        })
    }
    return files
}

private fun isIgnoredPath(path: String): Boolean =
    path.split('/').any { it.lowercase() in IGNORED_DIRS }

// 按文件修改时间缓存内容，连续输入同一批文件时不再重复读盘
private fun loadText(file: VirtualFile): String? {
    val stamp = file.modificationStamp
    TextCache[file.path]?.let { if (it.first == stamp) return it.second }
    val text = ReadAction.compute<String?, RuntimeException> {
        try {
            VfsUtilCore.loadText(file)
        } catch (e: Exception) {
            null
        }
    } ?: return null
    TextCache[file.path] = stamp to text
    return text
}

private fun scanFile(
    file: VirtualFile,
    text: String,
    lineStarts: IntArray,
    entries: List<I18nEntry>,
    out: MutableList<I18nUsage>
) {
    for (entry in entries) {
        for (needle in needlesOf(entry.key)) {
            var index = text.indexOf(needle)
            while (index >= 0) {
                out += I18nUsage(file, index, lineOf(lineStarts, index), entry.key, entry.value)
                if (out.size >= I18N_MAX_RESULTS) return
                index = text.indexOf(needle, index + 1)
            }
        }
    }
}

private fun needlesOf(key: String) = listOf("'$key'", "\"$key\"")

private fun lineStartsOf(text: String): IntArray {
    val starts = ArrayList<Int>()
    starts += 0
    for (i in text.indices) {
        if (text[i] == '\n') starts += i + 1
    }
    return starts.toIntArray()
}

private fun lineOf(starts: IntArray, offset: Int): Int {
    val index = java.util.Arrays.binarySearch(starts, offset)
    return if (index >= 0) index + 1 else -index - 1
}


/**
 * 全局搜索：界面仿「在文件中查找」，输入译文 -> zh.json 匹配 key -> 按文件分组列出引用位置
 */
class I18nGlobalSearchDialog(private val project: Project) : DialogWrapper(project) {

    private data class FileGroup(val file: VirtualFile, val count: Int)

    private val keywordField = JBTextField()
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(treeModel)
    private val statusLabel = JBLabel("输入译文后自动搜索")

    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this.disposable)
    private var pending: Future<*>? = null
    @Volatile
    private var generation = 0

    // 当前关键字，渲染时用来给命中的译文片段上色
    @Volatile
    private var activeKeyword = ""

    init {
        title = "在文件中查找"
        isModal = false
        init()
        keywordField.emptyText.text = "输入译文，如：登录"
        keywordField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = scheduleSearch()
        })
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = UsageTreeRenderer()
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) navigateToSelected()
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    e.consume()
                    navigateToSelected()
                }
            }
        })
        // 焦点留在输入框时也能上下切换选中结果、回车跳转
        keywordField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        e.consume()
                        selectUsage(1)
                    }

                    KeyEvent.VK_UP -> {
                        e.consume()
                        selectUsage(-1)
                    }

                    KeyEvent.VK_ENTER -> {
                        e.consume()
                        navigateToSelected()
                    }
                }
            }
        })
    }

    override fun createCenterPanel(): JComponent {
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 10, 0)).apply {
            isOpaque = false
            add(JBLabel("↑↓ 切换  Enter 跳转").apply { foreground = JBColor.GRAY })
            add(statusLabel)
        }
        val top = JPanel(BorderLayout(10, 0)).apply {
            border = JBUI.Borders.empty(8, 10)
            add(JBLabel("译文"), BorderLayout.WEST)
            add(keywordField, BorderLayout.CENTER)
            add(right, BorderLayout.EAST)
        }
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(780), JBUI.scale(460))
            add(top, BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(tree), BorderLayout.CENTER)
        }
    }

    override fun createActions(): Array<Action> = emptyArray()

    override fun getPreferredFocusedComponent(): JComponent = keywordField

    private fun scheduleSearch() {
        alarm.cancelAllRequests()
        alarm.addRequest({ startSearch() }, 250)
    }

    private fun startSearch() {
        val keyword = keywordField.text.trim()
        if (keyword.isEmpty()) {
            treeModel.setRoot(DefaultMutableTreeNode())
            statusLabel.text = "输入译文后自动搜索"
            return
        }
        pending?.cancel(true)
        val current = ++generation
        activeKeyword = keyword
        statusLabel.foreground = JBColor.GRAY
        statusLabel.text = "搜索中…"
        pending = AppExecutorUtil.getAppExecutorService().submit {
            val usages = collectI18nUsages(project, keyword) { current != generation }
            ApplicationManager.getApplication().invokeLater({
                if (current != generation || isDisposed) return@invokeLater
                treeModel.setRoot(buildRoot(usages))
                TreeUtil.expandAll(tree)
                statusLabel.text = if (usages.isEmpty()) {
                    "未找到引用"
                } else {
                    "共 ${usages.size} 处引用，${usages.distinctBy { it.file }.size} 个文件"
                }
                statusLabel.foreground = if (usages.isEmpty()) JBColor.GRAY else ACCENT_COLOR
            }, ModalityState.any())
        }
    }

    private fun buildRoot(usages: List<I18nUsage>): DefaultMutableTreeNode {
        val root = DefaultMutableTreeNode()
        usages.groupBy { it.file }.forEach { (file, items) ->
            val fileNode = DefaultMutableTreeNode(FileGroup(file, items.size))
            items.forEach { fileNode.add(DefaultMutableTreeNode(it)) }
            root.add(fileNode)
        }
        return root
    }

    /** 只在命中的行之间移动选中项，跳过文件分组节点 */
    private fun selectUsage(delta: Int) {
        val rows = (0 until tree.rowCount).filter { row ->
            val node = tree.getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode
            node?.userObject is I18nUsage
        }
        if (rows.isEmpty()) return
        val current = rows.indexOf(tree.leadSelectionRow)
        val target = if (current < 0) {
            if (delta > 0) rows.first() else rows.last()
        } else {
            rows[(current + delta).coerceIn(0, rows.lastIndex)]
        }
        tree.setSelectionRow(target)
        tree.scrollRowToVisible(target)
    }

    private fun navigateToSelected() {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val usage = node.userObject as? I18nUsage ?: return
        // 先关弹窗再跳转：打开编辑器的过程可能较慢，否则弹窗会晚一步才消失
        close(OK_EXIT_CODE)
        ApplicationManager.getApplication().invokeLater({
            OpenFileDescriptor(project, usage.file, usage.offset).navigate(true)
        }, ModalityState.any())
    }

    private inner class UsageTreeRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            // 树模型里传进来的是 DefaultMutableTreeNode，取它的 userObject 才是真正的数据
            val item = (value as? DefaultMutableTreeNode)?.userObject
            when (item) {
                is FileGroup -> {
                    icon = item.file.fileType.icon
                    val base = project.guessProjectDir()
                    val path = if (base != null) {
                        VfsUtilCore.getRelativePath(item.file, base, '/') ?: item.file.path
                    } else {
                        item.file.path
                    }
                    append(item.file.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  $path", PATH_ATTRIBUTES)
                    append("  ${item.count} 处", COUNT_ATTRIBUTES)
                }

                is I18nUsage -> {
                    append("${item.line}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  ")
                    appendWithMatch(item.value, activeKeyword)
                    append("   ${item.key}", KEY_ATTRIBUTES)
                }

                else -> {}
            }
        }

        // 把译文里命中关键字的那一段单独染色，其余保持常规
        private fun appendWithMatch(text: String, keyword: String) {
            if (keyword.isEmpty()) {
                append(text, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                return
            }
            var from = 0
            while (true) {
                val index = text.indexOf(keyword, from, ignoreCase = true)
                if (index < 0) {
                    append(text.substring(from), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    return
                }
                if (index > from) append(text.substring(from, index), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                append(text.substring(index, index + keyword.length), MATCH_ATTRIBUTES)
                from = index + keyword.length
            }
        }
    }
}

class I18nGlobalSearchAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        I18nGlobalSearchDialog(project).show()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
