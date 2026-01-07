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

/**
 * I18n Inline Hints Provider
 * 只处理一级 key，支持 hover 点击跳转到 zh.json
 */
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

        if (!file.fileType.name.contains("Vue", true)
            && file.language.id !in setOf("JavaScript", "TypeScript")
        ) return null

        val project = file.project

        // ================================
        // 读取一级 JSON
        // ================================
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

                // ================================
                // 处理 Vue 模板里注入的 JS
                // ================================
                ilm.enumerate(element) { injectedPsi, _ ->

                    val literal = PsiTreeUtil.findChildOfType(
                        injectedPsi,
                        JSLiteralExpression::class.java
                    ) ?: return@enumerate

                    if (!literal.isStringLiteral) return@enumerate
                    val key = literal.stringValue ?: return@enumerate
                    if (key.isBlank()) return@enumerate

                    val value = keyValueMap[key] ?: return@enumerate

                    val offset = ilm.injectedToHost(injectedPsi, literal.textRange).endOffset
                    if (!processedOffsets.add(offset)) return@enumerate

                    val targetElement = zhPsi?.let { findJsonValueElement(it, key) }

                    val presentation = factory.smallText(" $value ").let { textPresentation ->
                        factory.referenceOnHover(textPresentation) { _, _ ->
                            targetElement?.let {
                                OpenFileDescriptor(project, it.containingFile.virtualFile, it.textOffset).navigate(true)
                            }
                        }
                    }

                    sink.addInlineElement(offset, true, factory.roundWithBackground(presentation))
                }

                // ================================
                // 处理普通 JS / TS 文件
                // ================================
                if (element is JSLiteralExpression && element.isStringLiteral) {

                    val key = element.stringValue ?: return true
                    if (key.isBlank()) return true

                    val value = keyValueMap[key] ?: return true
                    val offset = element.textRange.endOffset
                    if (!processedOffsets.add(offset)) return true

                    val targetElement = zhPsi?.let { findJsonValueElement(it, key) }

                    val presentation = factory.smallText(" $value ").let { textPresentation ->
                        factory.referenceOnHover(textPresentation) { _, _ ->
                            targetElement?.let {
                                OpenFileDescriptor(project, it.containingFile.virtualFile, it.textOffset).navigate(true)
                            }
                        }
                    }

                    sink.addInlineElement(offset, true, factory.roundWithBackground(presentation))
                }

                return true
            }
        }
    }

    // ================================
    // 辅助方法
    // ================================

    private fun findZhFile(project: Project): VirtualFile? {
        val settings = I18nSettings.getInstance(project)
        val path = settings.state.zhFilePath.takeIf { it.isNotBlank() } ?: "src/locales/zh.json"
        val baseDir = project.guessProjectDir() ?: return null
        return baseDir.findFileByRelativePath(path)
    }

    private fun readJson(file: VirtualFile): Map<String, String> {
        val content = String(file.contentsToByteArray(), Charsets.UTF_8)
        val parser = SimpleJsonParser()
        return parser.parseJson(content)  // 只解析一级 key
    }

    private fun findJsonValueElement(jsonFile: JsonFile, key: String): PsiElement? {
        val jsonObject = PsiTreeUtil.findChildOfType(jsonFile, JsonObject::class.java) ?: return null
        val prop = PsiTreeUtil.findChildrenOfType(jsonObject, JsonProperty::class.java)
            .firstOrNull { it.name == key } ?: return null
        return prop.value  // 返回 value 部分，用于跳转
    }
}
