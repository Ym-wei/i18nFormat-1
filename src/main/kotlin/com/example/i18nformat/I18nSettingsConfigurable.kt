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
    private val textFields = mutableListOf<JTextField>()

    override fun getDisplayName(): String = "I18n Format"

    override fun createComponent(): JComponent? {
        panel = JPanel(BorderLayout())

        val wrap = JPanel()
        wrap.layout = BoxLayout(wrap, BoxLayout.Y_AXIS)

        settings.state.zhFilePaths.forEachIndexed { index, path ->
            val label = JLabel("Language JSON Path ${index + 1} (relative to project root):")
            val textField = JTextField(path)
            textField.columns = 40
            textField.maximumSize = Dimension(Int.MAX_VALUE, textField.preferredSize.height)
            textField.alignmentX = Component.LEFT_ALIGNMENT
            textFields.add(textField)

            wrap.add(label)
            wrap.add(Box.createVerticalStrut(4))
            wrap.add(textField)
            wrap.add(Box.createVerticalStrut(8))
        }

        panel!!.add(wrap, BorderLayout.NORTH)
        return panel
    }

    override fun isModified(): Boolean {
        return textFields.map { it.text.trim() } != settings.state.zhFilePaths
    }

    override fun apply() {
        settings.state.zhFilePaths = textFields.map { it.text.trim() }.take(5).toMutableList()
        // 确保第一个不为空
        if (settings.state.zhFilePaths[0].isEmpty()) {
            settings.state.zhFilePaths[0] = "src/locales/zh.json"
        }
    }
}
