package com.example.i18nformat  // 声明包名

// 导入 IntelliJ 提示相关类
import com.intellij.codeInsight.hints.*
import com.intellij.lang.injection.InjectedLanguageManager          // 用于处理注入语言，如 Vue 模板里的 JS
import com.intellij.lang.javascript.psi.JSLiteralExpression       // JS 字符串字面量
import com.intellij.openapi.editor.Editor                         // 编辑器对象
import com.intellij.openapi.project.Project                       // 项目对象
import com.intellij.openapi.vfs.VirtualFile                       // 虚拟文件对象
import com.intellij.psi.PsiElement                                // PSI 元素
import com.intellij.psi.PsiFile                                   // PSI 文件
import com.intellij.psi.util.PsiTreeUtil                          // PSI 工具类
import javax.swing.JComponent                                     // Swing 组件
import com.intellij.openapi.project.guessProjectDir

@Suppress("UnstableApiUsage")  // 忽略 API 不稳定警告
class I18nInlayHintsProvider : InlayHintsProvider<NoSettings> {  // 定义 I18n 内联提示提供者，实现 InlayHintsProvider 接口

    override val key: SettingsKey<NoSettings> =
        SettingsKey("i18n.inline.hints")  // 提示设置的唯一 key

    override val name: String = "I18n Inline Hint"  // 提示名称

    override val previewText: String =
        "t('home.title')"  // 配置界面显示的预览文本

    override fun createSettings(): NoSettings = NoSettings()  // 提示没有自定义设置

    override fun createConfigurable(settings: NoSettings): ImmediateConfigurable =
        object : ImmediateConfigurable {  // 配置界面组件
            override fun createComponent(listener: ChangeListener): JComponent {
                return javax.swing.JPanel()  // 返回一个空 JPanel，占位
            }
        }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: NoSettings,
        sink: InlayHintsSink
    ): InlayHintsCollector? {

        println("File language = ${file.language.id} , type=${file.fileType.name}")  // 打印文件类型，调试用

        // 判断文件类型：非 Vue 文件，且非 JS/TS，就不处理
        if (!file.fileType.name.contains("Vue", true)
            && file.language.id !in setOf("JavaScript", "TypeScript")
        ) {
            return null  // 不处理该文件
        }

        return object : FactoryInlayHintsCollector(editor) {  // 返回自定义收集器

            private val processedOffsets = mutableSetOf<Int>()  // 用于去重，避免重复显示相同文本

            override fun collect(
                element: PsiElement,
                editor: Editor,
                sink: InlayHintsSink
            ): Boolean {

                val project = file.project  // 获取项目对象
                val ilm = InjectedLanguageManager.getInstance(project)  // 获取注入语言管理器

                // ================================
                // 1️⃣ 处理 Vue 模板里注入的 JS
                // ================================
                ilm.enumerate(element) { injectedPsi, _ ->  // 遍历 element 内所有注入的 PSI

                    val literal = PsiTreeUtil.findChildOfType(
                        injectedPsi,
                        JSLiteralExpression::class.java  // 查找 JS 字符串字面量
                    ) ?: return@enumerate  // 没找到就跳过

                    if (!literal.isStringLiteral) return@enumerate  // 不是字符串就跳过
                    val key = literal.stringValue ?: return@enumerate  // 获取字符串值，空就跳过
                    if (key.isBlank()) return@enumerate  // 空字符串也跳过

                    val zhFile = findZhFile(project) ?: return@enumerate  // 查找 zh.json 文件
                    val content = String(zhFile.contentsToByteArray(), Charsets.UTF_8)  // 读取文件内容

                    val parser = SimpleJsonParser()  // 自定义简单 JSON 解析器
                    val json = parser.parseJson(content)  // 解析 JSON
                    val value = parser.getValue(json, key) ?: return@enumerate  // 根据 key 获取中文翻译，没有就跳过

                    val hostRange = ilm.injectedToHost(injectedPsi, literal.textRange)  // 将注入语言的偏移转换到宿主文件
                    val offset = hostRange.endOffset  // 提示位置使用字符串结束位置

                    if (!processedOffsets.add(offset)) return@enumerate  // 去重，避免重复提示

                    sink.addInlineElement(  // 添加内联提示
                        offset,
                        true,  // 展示在文本后
                        factory.roundWithBackground(
                            factory.smallText(" $value ")  // 显示中文值，带圆角背景
                        )
                    )
                }

                // ================================
                // 2️⃣ 处理普通 JS / TS 文件
                // ================================
                if (element is JSLiteralExpression && element.isStringLiteral) {  // 判断是否 JS 字符串字面量

                    val key = element.stringValue ?: return true  // 获取 key
                    if (key.isBlank()) return true  // 空字符串跳过

                    val zhFile = findZhFile(file.project) ?: return true  // 获取 zh.json
                    val content = String(zhFile.contentsToByteArray(), Charsets.UTF_8)  // 读取内容

                    val parser = SimpleJsonParser()  // JSON 解析器
                    val json = parser.parseJson(content)  // 解析 JSON
                    val value = parser.getValue(json, key) ?: return true  // 获取中文值

                    val offset = element.textRange.endOffset  // 提示位置
                    if (!processedOffsets.add(offset)) return true  // 去重

                    sink.addInlineElement(  // 添加内联提示
                        offset,
                        true,
                        factory.roundWithBackground(
                            factory.smallText(" $value ")  // 显示中文文本
                        )
                    )
                }

                return true  // 返回 true 表示继续处理其他元素
            }
        }
    }

    private fun findZhFile(project: Project): VirtualFile? {
        // 1️⃣ 获取当前项目的 I18n 配置
        val settings = I18nSettings.getInstance(project)
        val path = settings.state.zhFilePath.takeIf { it.isNotBlank() } ?: "src/locales/zh.json"

        // 2️⃣ 获取项目根目录
        val baseDir = project.guessProjectDir() ?: return null

        // 3️⃣ 查找相对路径对应的文件
        return baseDir.findFileByRelativePath(path)
    }

}
