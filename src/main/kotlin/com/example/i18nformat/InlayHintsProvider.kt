package com.example.i18nformat

import com.intellij.codeInsight.hints.*
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
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

        println("File language = ${file.language.id}")

        if (file.language.id !in setOf("JavaScript", "TypeScript", "Vue")) {
            return null
        }

        return object : FactoryInlayHintsCollector(editor) {

            override fun collect(
                element: PsiElement,
                editor: Editor,
                sink: InlayHintsSink
            ): Boolean {

                // ⭐⭐⭐ 核心：只处理 JS 字符串字面量
                if (element !is JSLiteralExpression) return true
                if (!element.isStringLiteral) return true

                val key = element.stringValue ?: return true
                if (key.isBlank()) return true

                val zhFile = findZhFile(file.project) ?: return true
                val content = String(zhFile.contentsToByteArray(), Charsets.UTF_8)

                val parser = SimpleJsonParser()
                val json = parser.parseJson(content)
                val value = parser.getValue(json, key) ?: return true

                sink.addInlineElement(
                    element.textRange.endOffset,
                    true,
                    factory.roundWithBackground(
                        factory.smallText(" $value ")
                    )
                )

                return true
            }
        }
    }

    private fun findZhFile(project: Project): VirtualFile? {
        return project.baseDir
            ?.findFileByRelativePath("src/locales/zh.json")
    }
}
