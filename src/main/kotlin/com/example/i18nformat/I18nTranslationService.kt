package com.example.i18nformat

import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * 一条翻译记录：完整 key 路径、译文、所在文件、译文在文件中的偏移量
 */
data class I18nEntry(
    val key: String,
    val value: String,
    val file: VirtualFile,
    val offset: Int
)

/**
 * 汇总配置中的 zh 文件，提供译文查询。
 * 结果按文件修改时间缓存，避免每次搜索都解析 JSON。
 */
@Service(Service.Level.PROJECT)
class I18nTranslationService(private val project: Project) {

    private var cachedSignature: String? = null
    private var cachedEntries: List<I18nEntry> = emptyList()

    /**
     * 按译文内容匹配（忽略大小写），keyword 为空时返回空列表
     */
    fun findEntries(keyword: String): List<I18nEntry> {
        val text = keyword.trim()
        if (text.isEmpty()) return emptyList()
        return loadEntries().filter { it.value.contains(text, ignoreCase = true) }
    }

    @Synchronized
    private fun loadEntries(): List<I18nEntry> {
        val files = resolveTranslationFiles()
        val signature = buildSignature(files)
        if (signature == cachedSignature) return cachedEntries

        cachedEntries = ReadAction.compute<List<I18nEntry>, RuntimeException> {
            val result = mutableListOf<I18nEntry>()
            files.forEach { collectFromFile(it, result) }
            result
        }
        cachedSignature = signature
        return cachedEntries
    }

    private fun resolveTranslationFiles(): List<VirtualFile> {
        val baseDir = project.guessProjectDir() ?: return emptyList()
        return I18nSettings.getInstance(project).state.zhFilePaths
            .filter { it.isNotBlank() }
            .mapNotNull { baseDir.findFileByRelativePath(it.trim()) }
    }

    private fun buildSignature(files: List<VirtualFile>): String {
        val paths = I18nSettings.getInstance(project).state.zhFilePaths.joinToString(",")
        val stamps = files.joinToString("|") {
            "${it.path}:${it.modificationStamp}:${it.length}"
        }
        return "$paths#$stamps"
    }

    private fun collectFromFile(file: VirtualFile, out: MutableList<I18nEntry>) {
        val psiFile = PsiManager.getInstance(project).findFile(file)
        val root = if (psiFile is JsonFile) {
            PsiTreeUtil.findChildOfType(psiFile, JsonObject::class.java)
        } else {
            null
        }

        if (root != null) {
            collectObject(root, "", file, out)
        } else {
            // 非标准 JSON 或 PSI 不可用时退化为文本解析，跳转到文件开头
            SimpleJsonParser()
                .parseJson(String(file.contentsToByteArray(), Charsets.UTF_8))
                .forEach { (key, value) -> out += I18nEntry(key, value, file, 0) }
        }
    }

    private fun collectObject(
        obj: JsonObject,
        prefix: String,
        file: VirtualFile,
        out: MutableList<I18nEntry>
    ) {
        for (property in obj.propertyList) {
            val name = property.name ?: continue
            val key = if (prefix.isEmpty()) name else "$prefix.$name"

            when (val value = property.value) {
                is JsonStringLiteral -> out += I18nEntry(key, value.value, file, value.textOffset)
                is JsonObject -> collectObject(value, key, file, out)
            }
        }
    }
}
