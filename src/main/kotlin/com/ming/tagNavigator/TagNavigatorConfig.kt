package com.ming.tagNavigator

import com.google.gson.Gson
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore

object TagNavigatorConfig {
    private const val CONFIG_FILE = "tag-navigator.json"

    fun load(project: Project): Map<String, String> {
        val baseDir = project.baseDir ?: return emptyMap()
        val configFile = baseDir.findChild(CONFIG_FILE) ?: return emptyMap()
        return try {
            val text = VfsUtilCore.loadText(configFile)
            Gson().fromJson(text, Map::class.java) as Map<String, String>
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
