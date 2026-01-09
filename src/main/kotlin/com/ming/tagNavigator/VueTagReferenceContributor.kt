package com.ming.tagNavigator

import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.xml.XmlTag
import com.intellij.util.ProcessingContext

class VueTagReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {

        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(XmlTag::class.java),
            object : PsiReferenceProvider() {
                override fun getReferencesByElement(
                    element: PsiElement,
                    context: ProcessingContext
                ): Array<PsiReference> {

                    // 只处理 XmlTag
                    val tag = element as? XmlTag ?: return PsiReference.EMPTY_ARRAY
                    val tagName = tag.name

                    // 只处理自定义标签（带 "-"）
                    if (!tagName.contains("-")) return PsiReference.EMPTY_ARRAY

                    // 只处理 JSON 配置的标签，忽略 class/id 等属性
                    val map = TagNavigatorConfig.load(element.project)
                    if (!map.containsKey(tagName)) return PsiReference.EMPTY_ARRAY

                    // 返回跳转引用
                    return arrayOf(VueTagReference(tag, tagName))
                }
            }
        )
    }
}
