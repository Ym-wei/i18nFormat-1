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
    override val previewText: String = "t('home.title')"
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

        return object : FactoryInlayHintsCollector(editor) {

            private val processedOffsets = mutableSetOf<Int>()

            override fun collect(
                element: PsiElement,
                editor: Editor,
                sink: InlayHintsSink
            ): Boolean {

                val project = file.project
                val ilm = InjectedLanguageManager.getInstance(project)

                // ================================
                // 1️⃣ 处理 Vue 模板里注入的 JS
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

                    // ========== 新增/修改部分 START ==========
                    val zhPsi = PsiManager.getInstance(project).findFile(zhFile) as? JsonFile ?: return@enumerate
                    val targetElement = findJsonValueElement(zhPsi, key)  // 查找 JSON key 对应 PsiElement
                    val hostRange = ilm.injectedToHost(injectedPsi, literal.textRange)
                    val offset = hostRange.endOffset
                    if (!processedOffsets.add(offset)) return@enumerate

                    val presentation = factory.smallText(" $value ").let {
                        factory.referenceOnHover(it) { _, _ ->  // <- 修复类型匹配
                            if (targetElement?.isValid == true) {
                                val file = targetElement.containingFile.virtualFile
                                val offset = targetElement.textOffset
                                OpenFileDescriptor(project, file, offset).navigate(true)
                            }
                        }
                    }

                    sink.addInlineElement(offset, true, factory.roundWithBackground(presentation))
                    // ========== 新增/修改部分 END ==========
                }

                // ================================
                // 2️⃣ 处理普通 JS / TS 文件
                // ================================
                if (element is JSLiteralExpression && element.isStringLiteral) {

                    val key = element.stringValue ?: return true
                    if (key.isBlank()) return true

                    val zhFile = findZhFile(file.project) ?: return true
                    val content = String(zhFile.contentsToByteArray(), Charsets.UTF_8)

                    val parser = SimpleJsonParser()
                    val json = parser.parseJson(content)
                    val value = parser.getValue(json, key) ?: return true

                    // ========== 新增/修改部分 START ==========
                    val zhPsi = PsiManager.getInstance(file.project).findFile(zhFile) as? JsonFile ?: return true
                    val targetElement = findJsonValueElement(zhPsi, key)
                    val offset = element.textRange.endOffset
                    if (!processedOffsets.add(offset)) return true

                    val presentation = factory.smallText(" $value ").let {
                        factory.referenceOnHover(it) { _, _ ->  // <- 修复类型匹配
                            if (targetElement?.isValid == true) {
                                val file = targetElement.containingFile.virtualFile
                                val offset = targetElement.textOffset
                                OpenFileDescriptor(project, file, offset).navigate(true)
                            }
                        }
                    }

                    sink.addInlineElement(offset, true, factory.roundWithBackground(presentation))
                    // ========== 新增/修改部分 END ==========
                }

                return true
            }
        }
    }

    private fun findZhFile(project: Project): VirtualFile? {
        val settings = I18nSettings.getInstance(project)
        val path = settings.state.zhFilePath.takeIf { it.isNotBlank() } ?: "src/locales/zh.json"
        val baseDir = project.guessProjectDir() ?: return null
        return baseDir.findFileByRelativePath(path)
    }

    private fun findJsonValueElement(jsonFile: JsonFile, key: String): PsiElement? {
        val jsonObject = PsiTreeUtil.findChildOfType(jsonFile, JsonObject::class.java) ?: return null
        val prop = PsiTreeUtil.findChildrenOfType(jsonObject, JsonProperty::class.java)
            .firstOrNull { it.name == key } ?: return null
        return prop.value  // <- 返回 value 部分
    }
}
