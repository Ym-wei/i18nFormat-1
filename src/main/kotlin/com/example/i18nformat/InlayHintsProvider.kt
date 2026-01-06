package com.example.i18nformat

import com.intellij.codeInsight.hints.*
import com.intellij.lang.injection.InjectedLanguageManager          // ⭐ 新增
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil                            // ⭐ 新增
import javax.swing.JComponent

@Suppress("UnstableApiUsage")
class I18nInlayHintsProvider : InlayHintsProvider<NoSettings> {

    override val key: SettingsKey<NoSettings> =
        SettingsKey("i18n.inline.hints")

    override val name: String = "I18n Inline Hint"

    override val previewText: String =
        "t('home.title')"

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

        println("File language = ${file.language.id} , type=${file.fileType.name}")

        // ⭐ 修改：Vue template fileType 不一定 language=Vue
        if (!file.fileType.name.contains("Vue", true)
            && file.language.id !in setOf("JavaScript", "TypeScript")
        ) {
            return null
        }

        return object : FactoryInlayHintsCollector(editor) {

            // ⭐⭐⭐ 新增：去重，避免你说的“很多很多重复文本”
            private val processedOffsets = mutableSetOf<Int>()

            override fun collect(
                element: PsiElement,
                editor: Editor,
                sink: InlayHintsSink
            ): Boolean {

                val project = file.project
                val ilm = InjectedLanguageManager.getInstance(project)

                // ================================
                // ⭐⭐⭐ 1️⃣ 处理 Vue Mustache 注入 JS
                // ================================
                ilm.enumerate(element) { injectedPsi, _ ->

                    val literal = PsiTreeUtil.findChildOfType(
                        injectedPsi,
                        JSLiteralExpression::class.java
                    ) ?: return@enumerate

                    if (!literal.isStringLiteral) return@enumerate
                    val key = literal.stringValue ?: return@enumerate
                    if (key.isBlank()) return@enumerate

                    val zhFile = findZhFile(project) ?: return@enumerate
                    val content = String(zhFile.contentsToByteArray(), Charsets.UTF_8)

                    val parser = SimpleJsonParser()
                    val json = parser.parseJson(content)
                    val value = parser.getValue(json, key) ?: return@enumerate

                    // ⭐⭐⭐ 关键：把 Injected offset 转 Host offset
                    val hostRange = ilm.injectedToHost(injectedPsi, literal.textRange)
                    val offset = hostRange.endOffset

                    if (!processedOffsets.add(offset)) return@enumerate   // ⭐ 去重复

                    sink.addInlineElement(
                        offset,
                        true,
                        factory.roundWithBackground(
                            factory.smallText(" $value ")
                        )
                    )
                }

                // ================================
                // ⭐⭐⭐ 2️⃣ 保留你原来的 JS / TS 处理
                // ================================
                if (element is JSLiteralExpression && element.isStringLiteral) {

                    val key = element.stringValue ?: return true
                    if (key.isBlank()) return true

                    val zhFile = findZhFile(file.project) ?: return true
                    val content = String(zhFile.contentsToByteArray(), Charsets.UTF_8)

                    val parser = SimpleJsonParser()
                    val json = parser.parseJson(content)
                    val value = parser.getValue(json, key) ?: return true

                    val offset = element.textRange.endOffset
                    if (!processedOffsets.add(offset)) return true   // ⭐ 去重复

                    sink.addInlineElement(
                        offset,
                        true,
                        factory.roundWithBackground(
                            factory.smallText(" $value ")
                        )
                    )
                }

                return true
            }
        }
    }

    private fun findZhFile(project: Project): VirtualFile? {
        return project.baseDir
            ?.findFileByRelativePath("src/locales/zh.json")
    }
}
