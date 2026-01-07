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
        var zhFilePath: String = "src/locales/zh.json"
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): I18nSettings = project.service()
    }
}
