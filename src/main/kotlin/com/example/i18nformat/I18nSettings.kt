package com.example.i18nformat

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@State(
    name = "I18nSettings",
    storages = [Storage("i18n-inline-settings.xml")]
)
@Service(Service.Level.PROJECT)
class I18nSettings : PersistentStateComponent<I18nSettings.State> {

    data class State(
        var zhFilePaths: MutableList<String> = mutableListOf(
            "src/locales/zh.json", "", "", "", ""
        )
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        // 确保总共有5个元素
        myState = State(
            zhFilePaths = (state.zhFilePaths + List(5) { "" }).take(5).toMutableList()
        )
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): I18nSettings = project.service()
    }
}
