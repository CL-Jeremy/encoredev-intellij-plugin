package dev.encore.intellij.annotators

import com.goide.highlighting.GoSyntaxHighlightingColors
import com.goide.psi.GoFunctionDeclaration
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil

class ApiDecls : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        // If it's not a comment, doesn't start with "//encore:" or is not
        // followed by a func definition, return as we're not interested in it
        if (element !is PsiComment || PsiTreeUtil.skipWhitespacesForward(element) !is GoFunctionDeclaration) {
            return
        }
        val comment: PsiComment = element

        // Parse the comment. Return if prefix is not known to us
        val (prefix, cfg, args) = parseApiAnnotation(comment)
        if (cfg == null) {
            return
        }

        // Skip the initial "//"
        val startOffset = comment.textRange.startOffset + 2

        // Highlight the "encore:" part of the comment as a comment keyword
        val directiveRange = TextRange.from(startOffset, prefix.length)
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(directiveRange)
            .textAttributes(GoSyntaxHighlightingColors.COMMENT_KEYWORD)
            .tooltip(cfg.tooltip)
            .create()

        // Highlight the rest of the comment
        var lastOffset = startOffset + prefix.length + 1
        val used = mutableSetOf<String>()

        for (part in args) {
            if (part.contains('=')) {
                // It's a field
                val splitPoint = part.indexOf('=')
                val fieldName = part.take(splitPoint)
                val fieldValue = part.drop(splitPoint + 1)
                val nameRange = TextRange.from(lastOffset, splitPoint)
                val valueRange = TextRange.from(lastOffset + splitPoint + 1, fieldValue.length)


                // Check it's not already used once
                if (used.contains(fieldName)) {
                    holder.newAnnotation(HighlightSeverity.ERROR, "This field cannot be used more than once per directive")
                        .range(nameRange)
                        .highlightType(ProblemHighlightType.GENERIC_ERROR)
                        .create()
                } else if (!cfg.fieldNames.contains(fieldName)) {
                    holder.newAnnotation(
                        HighlightSeverity.ERROR,
                        "Unknown field name, supported fields are:" + cfg.fieldNames.joinToString(", ")
                    )
                        .range(nameRange)
                        .highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL)
                        .create()
                } else {
                    // If it's valid then highlight it as so
                    holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .range(nameRange)
                        .textAttributes(GoSyntaxHighlightingColors.GO_BUILD_TAG)
                        .create()
                }

                highlightFieldValue(holder, fieldName, fieldValue, valueRange)

                used.add(fieldName)

            } else {
                // It's an option
                val partRange = TextRange.from(lastOffset, part.length)

                // Check it's not already used once
                if (used.contains(part)) {
                    // Otherwise, highlight it as an error
                    holder.newAnnotation(HighlightSeverity.ERROR, "This option cannot be used more than once per directive")
                        .range(partRange)
                        .highlightType(ProblemHighlightType.GENERIC_ERROR)
                        .create()
                } else if (!cfg.options.contains(part) && !part.startsWith("tag:")) {
                    // Otherwise, highlight it as an error
                    holder.newAnnotation(HighlightSeverity.ERROR, "This is not a valid option for this directive, supported options are: " + cfg.options.joinToString(", "))
                        .range(partRange)
                        .highlightType(ProblemHighlightType.LIKE_UNKNOWN_SYMBOL)
                        .create()
                } else {
                    // If it's valid then highlight it as so
                    holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .range(partRange)
                        .textAttributes(GoSyntaxHighlightingColors.GO_BUILD_TAG)
                        .create()
                }

                used.add(part)
            }

            lastOffset += part.length + 1
        }
    }

    private fun highlightFieldValue(holder: AnnotationHolder, fieldName: String, fieldValue: String, range: TextRange) {
        if (fieldName == "path" && (fieldValue.contains(":") || fieldValue.contains("*"))) {
            // highlight each segment starting with ":" as a different colour
            var lastIndex = range.startOffset
            for (part in fieldValue.split("/")) {
                // +1 for the slash, but the last one doesn't have one
                var length = part.length + 1
                if (lastIndex + length > range.endOffset) {
                    length = range.endOffset - lastIndex
                }

                if (part.startsWith(":") || part.startsWith("*")) {
                    val partRange =  if (length == part.length) {
                        TextRange.from(lastIndex, length)
                    } else {
                        // highlight the following slash as a string
                        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                            .range(TextRange.from(lastIndex + length - 1, 1))
                            .textAttributes(DefaultLanguageHighlighterColors.STRING)
                            .create()

                        TextRange.from(lastIndex, length - 1)
                    }

                    holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .range(partRange)
                        .textAttributes(GoSyntaxHighlightingColors.LOCAL_VARIABLE)
                        .create()
                } else {
                    val partRange = TextRange.from(lastIndex, length)
                    holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                        .range(partRange)
                        .textAttributes(DefaultLanguageHighlighterColors.STRING)
                        .create()
                }
                lastIndex += part.length + 1
            }
            return
        } else {
            // highlight the whole value at once
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(range)
                .textAttributes(DefaultLanguageHighlighterColors.STRING)
                .create()
        }
    }
}

private const val API_DECL_PREFIX = "//encore:"

private val PREFIXES = mapOf(
    "encore:api" to DeclCfg(
        "This defines an API endpoint",
        arrayOf("raw", "public", "private", "auth"),
        arrayOf("path", "method"),
    ),
    "encore:service" to DeclCfg(
        "This defines a service singleton which will be started up along side your service",
        arrayOf(),
        arrayOf(),
    ),
    "encore:authhandler" to DeclCfg(
        "This defines an authentication handler which will be used to authenticate requests for your whole application.",
        arrayOf(),
        arrayOf(),
    ),
    "encore:middleware" to DeclCfg(
        "This defines a middleware which will be used to process requests",
        arrayOf("global"),
        arrayOf("target"),
    )
)

fun isApiAnnotation(comment: PsiComment) = parseApiAnnotation(comment).second != null

private fun parseApiAnnotation(comment: PsiComment): Triple<String, DeclCfg?, List<String>> {
    val text = comment.text
    if (!text.startsWith(API_DECL_PREFIX)) {
        return Triple("", null, emptyList())
    }
    val parts = text.removePrefix("//").split(" ")
    val prefix = parts.getOrNull(0) ?: ""

    return Triple(prefix, PREFIXES[prefix], parts.drop(1))
}

data class DeclCfg(
    val tooltip: String,
    val options: Array<String>,
    val fieldNames: Array<String>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DeclCfg

        if (tooltip != other.tooltip) return false
        if (!options.contentEquals(other.options)) return false
        return fieldNames.contentEquals(other.fieldNames)
    }

    override fun hashCode(): Int {
        var result = tooltip.hashCode()
        result = 31 * result + options.contentHashCode()
        result = 31 * result + fieldNames.contentHashCode()
        return result
    }
}
