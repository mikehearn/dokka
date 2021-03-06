package org.jetbrains.dokka

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.html.entities.EntityConverter
import java.util.*

public fun buildContent(tree: MarkdownNode, linkResolver: (String) -> ContentBlock, inline: Boolean = false): MutableContent {
    val result = MutableContent()
    if (inline) {
        buildInlineContentTo(tree, result, linkResolver)
    }
    else {
        buildContentTo(tree, result, linkResolver)
    }
    return result
}

public fun buildContentTo(tree: MarkdownNode, target: ContentBlock, linkResolver: (String) -> ContentBlock) {
//    println(tree.toTestString())
    val nodeStack = ArrayDeque<ContentBlock>()
    nodeStack.push(target)

    tree.visit {node, processChildren ->
        val parent = nodeStack.peek()

        fun appendNodeWithChildren(content: ContentBlock) {
            nodeStack.push(content)
            processChildren()
            parent.append(nodeStack.pop())
        }

        when (node.type) {
            MarkdownElementTypes.ATX_1 -> appendNodeWithChildren(ContentHeading(1))
            MarkdownElementTypes.ATX_2 -> appendNodeWithChildren(ContentHeading(2))
            MarkdownElementTypes.ATX_3 -> appendNodeWithChildren(ContentHeading(3))
            MarkdownElementTypes.ATX_4 -> appendNodeWithChildren(ContentHeading(4))
            MarkdownElementTypes.ATX_5 -> appendNodeWithChildren(ContentHeading(5))
            MarkdownElementTypes.ATX_6 -> appendNodeWithChildren(ContentHeading(6))
            MarkdownElementTypes.UNORDERED_LIST -> appendNodeWithChildren(ContentUnorderedList())
            MarkdownElementTypes.ORDERED_LIST -> appendNodeWithChildren(ContentOrderedList())
            MarkdownElementTypes.LIST_ITEM ->  appendNodeWithChildren(ContentListItem())
            MarkdownElementTypes.EMPH -> appendNodeWithChildren(ContentEmphasis())
            MarkdownElementTypes.STRONG -> appendNodeWithChildren(ContentStrong())
            MarkdownElementTypes.CODE_SPAN -> appendNodeWithChildren(ContentCode())
            MarkdownElementTypes.CODE_BLOCK,
            MarkdownElementTypes.CODE_FENCE -> appendNodeWithChildren(ContentBlockCode())
            MarkdownElementTypes.PARAGRAPH -> appendNodeWithChildren(ContentParagraph())

            MarkdownElementTypes.INLINE_LINK -> {
                val label = node.child(MarkdownElementTypes.LINK_TEXT)?.child(MarkdownTokenTypes.TEXT)
                val destination = node.child(MarkdownElementTypes.LINK_DESTINATION)
                if (label != null) {
                    if (destination != null) {
                        val link = ContentExternalLink(destination.text)
                        link.append(ContentText(label.text))
                        parent.append(link)
                    } else {
                        val link = ContentExternalLink(label.text)
                        link.append(ContentText(label.text))
                        parent.append(link)
                    }
                }
            }
            MarkdownElementTypes.SHORT_REFERENCE_LINK,
            MarkdownElementTypes.FULL_REFERENCE_LINK -> {
                val label = node.child(MarkdownElementTypes.LINK_LABEL)?.child(MarkdownTokenTypes.TEXT)
                if (label != null) {
                    val link = linkResolver(label.text)
                    val linkText = node.child(MarkdownElementTypes.LINK_TEXT)?.child(MarkdownTokenTypes.TEXT)
                    link.append(ContentText(linkText?.text ?: label.text))
                    parent.append(link)
                }
            }
            MarkdownTokenTypes.WHITE_SPACE,
            MarkdownTokenTypes.EOL -> {
                if (keepWhitespace(nodeStack.peek()) && node.parent?.children?.last() != node) {
                    parent.append(ContentText(node.text))
                }
            }

            MarkdownTokenTypes.CODE -> {
                val block = ContentBlockCode()
                block.append(ContentText(node.text))
                parent.append(block)
            }

            MarkdownTokenTypes.TEXT -> {
                fun createEntityOrText(text: String): ContentNode {
                    if (text == "&amp;" || text == "&quot;" || text == "&lt;" || text == "&gt;") {
                        return ContentEntity(text)
                    }
                    if (text == "&") {
                        return ContentEntity("&amp;")
                    }
                    val decodedText = EntityConverter.replaceEntities(text, true, true)
                    if (decodedText != text) {
                        return ContentEntity(text)
                    }
                    return ContentText(text)
                }

                parent.append(createEntityOrText(node.text))
            }

            MarkdownTokenTypes.COLON,
            MarkdownTokenTypes.DOUBLE_QUOTE,
            MarkdownTokenTypes.LT,
            MarkdownTokenTypes.GT,
            MarkdownTokenTypes.LPAREN,
            MarkdownTokenTypes.RPAREN,
            MarkdownTokenTypes.LBRACKET,
            MarkdownTokenTypes.RBRACKET,
            MarkdownTokenTypes.CODE_FENCE_CONTENT -> {
                parent.append(ContentText(node.text))
            }
            else -> {
                processChildren()
            }
        }
    }
}

private fun keepWhitespace(node: ContentNode) = node is ContentParagraph || node is ContentSection

public fun buildInlineContentTo(tree: MarkdownNode, target: ContentBlock, linkResolver: (String) -> ContentBlock) {
    val inlineContent = tree.children.singleOrNull { it.type == MarkdownElementTypes.PARAGRAPH }?.children ?: listOf(tree)
    inlineContent.forEach {
        buildContentTo(it, target, linkResolver)
    }
}

