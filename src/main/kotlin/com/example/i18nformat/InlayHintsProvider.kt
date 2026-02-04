package com.example.i18nformat

import com.intellij.codeInsight.hints.*
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.json.psi.*
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import javax.swing.JComponent

@Suppress("UnstableApiUsage")
class I18nInlayHintsProvider : InlayHintsProvider<NoSettings> {

    override val key: SettingsKey<NoSettings> = SettingsKey("i18n.inline.hints")
    override val name: String = "I18n Inline Hint"
    override val previewText: String = "t('home')"

    override fun createSettings(): NoSettings = NoSettings()

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable =
        object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent {
                return javax.swing.JPanel()
            }
        }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ): InlayHintsCollector? {

        val isVue = file.fileType.name.contains("Vue", true)
        val isJsLike = file.language.id in setOf("JavaScript", "TypeScript", "ECMAScript 6")
        if (!isVue && !isJsLike) return null

        val project = file.project

        // ===== 1. 多个 i18n json 文件 =====
        val zhFiles = findZhFiles(project)
        if (zhFiles.isEmpty()) return null

        // ===== 2. 扁平 key（平级 key 直接命中）=====
        val flatKeyMap = readJsonFiles(zhFiles)

        // ===== 3. JsonObject 根（用于递归查找）=====
        val jsonRoots = buildJsonRoots(project, zhFiles)

        val ilm = InjectedLanguageManager.getInstance(project)

        return object : FactoryInlayHintsCollector(editor) {

            private val processedOffsets = mutableSetOf<Int>()

            override fun collect(
                element: PsiElement,
                editor: Editor,
                sink: InlayHintsSink
            ): Boolean {

                // ===== Vue 注入 JS =====
                ilm.enumerate(element) { injectedPsi, _ ->
                    val literal = PsiTreeUtil.findChildOfType(
                        injectedPsi,
                        JSLiteralExpression::class.java
                    ) ?: return@enumerate

                    handleLiteral(
                        literal = literal,
                        offset = ilm.injectedToHost(injectedPsi, literal.textRange).endOffset,
                        project = project,
                        flatKeyMap = flatKeyMap,
                        jsonRoots = jsonRoots,
                        processedOffsets = processedOffsets,
                        sink = sink
                    )
                }

                // ===== 普通 JS / TS =====
                if (element is JSLiteralExpression && element.isStringLiteral) {
                    handleLiteral(
                        literal = element,
                        offset = element.textRange.endOffset,
                        project = project,
                        flatKeyMap = flatKeyMap,
                        jsonRoots = jsonRoots,
                        processedOffsets = processedOffsets,
                        sink = sink
                    )
                }

                return true
            }
        }
    }

    // ================= 统一处理字符串 =================

    private fun FactoryInlayHintsCollector.handleLiteral(
        literal: JSLiteralExpression,
        offset: Int,
        project: Project,
        flatKeyMap: Map<String, String>,
        jsonRoots: List<JsonObject>,
        processedOffsets: MutableSet<Int>,
        sink: InlayHintsSink
    ) {
        val key = literal.stringValue ?: return
        if (key.isBlank()) return
        if (!processedOffsets.add(offset)) return

        // ===== 1. 先尝试平级 key =====
        val flatValue = flatKeyMap[key]
        val flatPsi = jsonRoots.firstNotNullOfOrNull { root ->
            root.findProperty(key)?.value
        }

        // ===== 2. 再尝试嵌套路径 =====
        val path = key.split(".")

        val nestedValue = jsonRoots.firstNotNullOfOrNull {
            findValueByPath(it, path)
        }

        val nestedPsi = jsonRoots.firstNotNullOfOrNull {
            findJsonValueByPath(it, path)
        }

        val value = flatValue ?: nestedValue ?: return
        val targetElement = flatPsi ?: nestedPsi

        val presentation = factory.smallText(" $value ").let { text ->
            factory.referenceOnHover(text) { e, _ ->
                val ctrlPressed = e?.isControlDown == true
                val metaPressed = e?.isMetaDown == true
                if (!(ctrlPressed || metaPressed)) return@referenceOnHover

                targetElement?.let {
                    OpenFileDescriptor(
                        project,
                        it.containingFile.virtualFile,
                        it.textOffset
                    ).navigate(true)
                }
            }
        }

        sink.addInlineElement(offset, true, factory.roundWithBackground(presentation))
    }

    // ================= JSON 解析辅助 =================

    private fun findZhFiles(project: Project): List<VirtualFile> {
        val baseDir = project.guessProjectDir() ?: return emptyList()
        val settings = I18nSettings.getInstance(project)

        return settings.state.zhFilePaths
            .filter { it.isNotBlank() } // 过滤空路径
            .mapNotNull { baseDir.findFileByRelativePath(it) }
    }

    private fun readJsonFiles(files: List<VirtualFile>): Map<String, String> {
        val parser = SimpleJsonParser()
        val result = LinkedHashMap<String, String>()

        files.forEach { file ->
            val content = String(file.contentsToByteArray(), Charsets.UTF_8)
            result.putAll(parser.parseJson(content))
        }
        return result
    }

    private fun buildJsonRoots(
        project: Project,
        files: List<VirtualFile>
    ): List<JsonObject> {
        val psiManager = PsiManager.getInstance(project)
        return files.mapNotNull { vf ->
            val jsonFile = psiManager.findFile(vf) as? JsonFile ?: return@mapNotNull null
            PsiTreeUtil.findChildOfType(jsonFile, JsonObject::class.java)
        }
    }

    // ===== 递归查 value（用于显示）=====
    private fun findValueByPath(
        jsonObject: JsonObject,
        path: List<String>
    ): String? {
        if (path.isEmpty()) return null
        val prop = jsonObject.findProperty(path.first()) ?: return null
        val value = prop.value ?: return null

        return if (path.size == 1) {
            (value as? JsonStringLiteral)?.value
        } else {
            val next = value as? JsonObject ?: return null
            findValueByPath(next, path.drop(1))
        }
    }

    // ===== 递归查 PsiElement（用于跳转）=====
    private fun findJsonValueByPath(
        jsonObject: JsonObject,
        path: List<String>
    ): PsiElement? {
        if (path.isEmpty()) return null
        val prop = jsonObject.findProperty(path.first()) ?: return null
        val value = prop.value ?: return null

        return if (path.size == 1) {
            value
        } else {
            val next = value as? JsonObject ?: return null
            findJsonValueByPath(next, path.drop(1))
        }
    }
}
