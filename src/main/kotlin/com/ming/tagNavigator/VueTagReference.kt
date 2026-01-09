package com.ming.tagNavigator

import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.*
import com.intellij.psi.xml.XmlTag
import com.intellij.openapi.util.TextRange
import java.io.File

class VueTagReference(
    tag: XmlTag,
    private val tagName: String
) : PsiReferenceBase<XmlTag>(
    tag,
    TextRange(1, 1 + tagName.length)
) {

    override fun resolve(): PsiElement? {
        val project = myElement.project
        val map = TagNavigatorConfig.load(project)

        val relativePath = map[tagName] ?: return null
        val basePath = project.basePath ?: return null

        val ioFile = File(basePath, relativePath)
        if (!ioFile.exists()) return null

        val vFile = VfsUtil.findFileByIoFile(ioFile, true) ?: return null
        return PsiManager.getInstance(project).findFile(vFile)
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
