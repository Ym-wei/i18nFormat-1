package com.example.i18nformat

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import javax.swing.*
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Component

class I18nSettingsConfigurable(private val project: Project) : Configurable {

    private val settings = I18nSettings.getInstance(project)
    private var panel: JPanel? = null
    private var textField: JTextField? = null

    override fun getDisplayName(): String = "I18n Format"

    override fun createComponent(): JComponent? {
        panel = JPanel(BorderLayout())

        // 单行输入框
        textField = JTextField(settings.state.zhFilePath)
        textField!!.columns = 40
        textField!!.maximumSize = Dimension(Int.MAX_VALUE, textField!!.preferredSize.height)
        textField!!.alignmentX = Component.LEFT_ALIGNMENT

        // 标签 + 输入框包装
        val label = JLabel("Language Package JSON Path (relative to project root):")
        val wrap = JPanel()
        wrap.layout = BoxLayout(wrap, BoxLayout.Y_AXIS)
        wrap.add(label)
        wrap.add(Box.createVerticalStrut(4)) // 间距
        wrap.add(textField)

        panel!!.add(wrap, BorderLayout.NORTH)
        return panel
    }

    override fun isModified(): Boolean {
        return textField?.text != settings.state.zhFilePath
    }

    override fun apply() {
        settings.state.zhFilePath = textField?.text?.trim().takeIf { it?.isNotEmpty() == true }
            ?: "src/locales/zh.json"
    }
}
