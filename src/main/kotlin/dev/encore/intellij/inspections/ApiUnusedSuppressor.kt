package dev.encore.intellij.inspections

import com.goide.psi.GoFunctionDeclaration
import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import dev.encore.intellij.annotators.ApiDecls.Companion.isApiAnnotation

class ApiUnusedSuppressor : InspectionSuppressor {
    companion object {
        private const val TOOL_ID = "GoUnusedExportedFunction"
    }

    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        if (element !is GoFunctionDeclaration || toolId != TOOL_ID) {
            return false
        }
        val prevElement = PsiTreeUtil.skipWhitespacesBackward(element)
        if (prevElement !is PsiComment) {
            return false
        }
        val comment: PsiComment = prevElement
        return isApiAnnotation(comment)
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String) = SuppressQuickFix.EMPTY_ARRAY!!

}
