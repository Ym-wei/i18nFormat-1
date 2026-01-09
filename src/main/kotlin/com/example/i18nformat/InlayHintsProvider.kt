package com.example.i18nformat

import com.intellij.codeInsight.hints.*
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonProperty
import javax.swing.JComponent
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.fileEditor.OpenFileDescriptor

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

        println("File language = ${file.language.id}")
        val fileNameBol = file.fileType.name.contains("Vue", true)
        val languageBol = file.language.id !in setOf("JavaScript", "TypeScript", "ECMAScript 6")
        if (!fileNameBol && languageBol) return null

        val project = file.project

        val zhFile = findZhFile(project) ?: return null
        val keyValueMap = readJson(zhFile)

        val ilm = InjectedLanguageManager.getInstance(project)
        val zhPsi = PsiManager.getInstance(project).findFile(zhFile) as? JsonFile

        return object : FactoryInlayHintsCollector(editor) {

            private val processedOffsets = mutableSetOf<Int>()

            override fun collect(
                element: PsiElement,
                editor: Editor,
                sink: InlayHintsSink
            ): Boolean {

                // ================= Vue 注入 JS =================
                ilm.enumerate(element) { injectedPsi, _ ->

                    val literal = PsiTreeUtil.findChildOfType(
                        injectedPsi,
                        JSLiteralExpression::class.java
                    ) ?: return@enumerate

                    handleLiteral(
                        literal = literal,
                        offset = ilm.injectedToHost(injectedPsi, literal.textRange).endOffset,
                        project = project,
                        zhPsi = zhPsi,
                        keyValueMap = keyValueMap,
                        processedOffsets = processedOffsets,
                        sink = sink
                    )
                }

                // ================= 普通 JS / TS =================
                if (element is JSLiteralExpression && element.isStringLiteral) {
                    handleLiteral(
                        literal = element,
                        offset = element.textRange.endOffset,
                        project = project,
                        zhPsi = zhPsi,
                        keyValueMap = keyValueMap,
                        processedOffsets = processedOffsets,
                        sink = sink
                    )
                }

                return true
            }
        }
    }


    // ================= 抽离的统一处理函数 =================
    private fun FactoryInlayHintsCollector.handleLiteral(
        literal: JSLiteralExpression,
        offset: Int,
        project: Project,
        zhPsi: JsonFile?,
        keyValueMap: Map<String, String>,
        processedOffsets: MutableSet<Int>,
        sink: InlayHintsSink
    ) {
        val key = literal.stringValue ?: return
        if (key.isBlank()) return

        val value = keyValueMap[key] ?: return
        if (!processedOffsets.add(offset)) return

        val targetElement = zhPsi?.let { findJsonValueElement(it, key) }

        val presentation = factory.smallText(" $value ").let { textPresentation ->
            factory.referenceOnHover(textPresentation) { e, _ ->

                // 👉 必须按 Ctrl 或 Command 才跳转
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

    // ================= 辅助 =================

    private fun findZhFile(project: Project): VirtualFile? {
        val settings = I18nSettings.getInstance(project)
        val path = settings.state.zhFilePath.takeIf { it.isNotBlank() } ?: "src/locales/zh.json"
        val baseDir = project.guessProjectDir() ?: return null
        return baseDir.findFileByRelativePath(path)
    }

    private fun readJson(file: VirtualFile): Map<String, String> {
        val content = String(file.contentsToByteArray(), Charsets.UTF_8)
        val parser = SimpleJsonParser()
        return parser.parseJson(content)
    }

    private fun findJsonValueElement(jsonFile: JsonFile, key: String): PsiElement? {
        val jsonObject = PsiTreeUtil.findChildOfType(jsonFile, JsonObject::class.java) ?: return null
        val prop = PsiTreeUtil.findChildrenOfType(jsonObject, JsonProperty::class.java)
            .firstOrNull { it.name == key } ?: return null
        return prop.value
    }
}
