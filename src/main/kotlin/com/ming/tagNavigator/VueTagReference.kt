package com.ming.tagNavigator

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiManager

class VueTagReference(
    element: PsiElement,
    private val tagName: String
) : PsiReferenceBase<PsiElement>(element, element.textRangeInParent) {

    override fun resolve(): PsiElement? {
        val project = element.project
        val map = TagNavigatorConfig.load(project)
        val path = map[tagName] ?: return null

        val baseDir = project.baseDir ?: return null
        val target: VirtualFile = baseDir.findFileByRelativePath(path) ?: return null
        return PsiManager.getInstance(project).findFile(target)
    }

    override fun getVariants(): Array<Any> = emptyArray()
}
