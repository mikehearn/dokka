package org.jetbrains.dokka

import org.jetbrains.dokka.LanguageService.RenderMode

/**
 * Implements [LanguageService] and provides rendering of symbols in Kotlin language
 */
class KotlinLanguageService : LanguageService {
    private val fullOnlyModifiers = setOf("public", "protected", "private", "inline", "noinline", "crossinline", "reified")

    override fun render(node: DocumentationNode, renderMode: RenderMode): ContentNode {
        return content {
            when (node.kind) {
                DocumentationNode.Kind.Package -> if (renderMode == RenderMode.FULL) renderPackage(node)
                in DocumentationNode.Kind.classLike -> renderClass(node, renderMode)

                DocumentationNode.Kind.EnumItem,
                DocumentationNode.Kind.ExternalClass -> if (renderMode == RenderMode.FULL) identifier(node.name)

                DocumentationNode.Kind.TypeParameter -> renderTypeParameter(node, renderMode)
                DocumentationNode.Kind.Type,
                DocumentationNode.Kind.UpperBound -> renderType(node, renderMode)

                DocumentationNode.Kind.Modifier -> renderModifier(node)
                DocumentationNode.Kind.Constructor,
                DocumentationNode.Kind.Function,
                DocumentationNode.Kind.CompanionObjectFunction -> renderFunction(node, renderMode)
                DocumentationNode.Kind.Property,
                DocumentationNode.Kind.CompanionObjectProperty -> renderProperty(node, renderMode)
                else -> identifier(node.name)
            }
        }
    }

    override fun renderName(node: DocumentationNode): String {
        return when (node.kind) {
            DocumentationNode.Kind.Constructor -> node.owner!!.name
            else -> node.name
        }
    }

    override fun summarizeSignatures(nodes: List<DocumentationNode>): ContentNode? {
        if (nodes.size < 2) return null
        val receiverKind = nodes.getReceiverKind() ?: return null
        val functionWithTypeParameter = nodes.firstOrNull { it.details(DocumentationNode.Kind.TypeParameter).any() } ?: return null
        return content {
            val typeParameter = functionWithTypeParameter.details(DocumentationNode.Kind.TypeParameter).first()
            if (functionWithTypeParameter.kind == DocumentationNode.Kind.Function) {
                renderFunction(functionWithTypeParameter, RenderMode.SUMMARY, SummarizingMapper(receiverKind, typeParameter.name))
            }
            else {
                renderProperty(functionWithTypeParameter, RenderMode.SUMMARY, SummarizingMapper(receiverKind, typeParameter.name))
            }
        }
    }

    private fun List<DocumentationNode>.getReceiverKind(): ReceiverKind? {
        val qNames = map { it.getReceiverQName() }.filterNotNull()
        if (qNames.size != size)
            return null

        return ReceiverKind.values.firstOrNull { kind -> qNames.all { it in kind.classes } }
    }

    private fun DocumentationNode.getReceiverQName(): String? {
        if (kind != DocumentationNode.Kind.Function && kind != DocumentationNode.Kind.Property) return null
        val receiver = details(DocumentationNode.Kind.Receiver).singleOrNull() ?: return null
        return receiver.detail(DocumentationNode.Kind.Type).qualifiedNameFromType()
    }

    companion object {
        private val arrayClasses = setOf(
                "kotlin.Array",
                "kotlin.BooleanArray",
                "kotlin.ByteArray",
                "kotlin.CharArray",
                "kotlin.ShortArray",
                "kotlin.IntArray",
                "kotlin.LongArray",
                "kotlin.FloatArray",
                "kotlin.DoubleArray"
        )

        private val arrayOrListClasses = setOf("kotlin.List") + arrayClasses

        private val iterableClasses = setOf(
                "kotlin.Collection",
                "kotlin.Sequence",
                "kotlin.Iterable",
                "kotlin.Map",
                "kotlin.String",
                "kotlin.CharSequence") + arrayOrListClasses
    }

    private enum class ReceiverKind(val receiverName: String, val classes: Collection<String>) {
        ARRAY("any_array", arrayClasses),
        ARRAY_OR_LIST("any_array_or_list", arrayOrListClasses),
        ITERABLE("any_iterable", iterableClasses),
    }

    interface SignatureMapper {
        fun renderReceiver(receiver: DocumentationNode, to: ContentBlock)
    }

    private class SummarizingMapper(val kind: ReceiverKind, val typeParameterName: String): SignatureMapper {
        override fun renderReceiver(receiver: DocumentationNode, to: ContentBlock) {
            to.append(ContentIdentifier(kind.receiverName, IdentifierKind.SummarizedTypeName))
            to.text("<$typeParameterName>")
        }
    }

    private fun ContentBlock.renderPackage(node: DocumentationNode) {
        keyword("package")
        text(" ")
        identifier(node.name)
    }

    private fun ContentBlock.renderList(nodes: List<DocumentationNode>, separator: String = ", ",
                                        noWrap: Boolean = false, renderItem: (DocumentationNode) -> Unit) {
        if (nodes.none())
            return
        renderItem(nodes.first())
        nodes.drop(1).forEach {
            if (noWrap) {
                symbol(separator.removeSuffix(" "))
                nbsp()
            } else {
                symbol(separator)
            }
            renderItem(it)
        }
    }

    private fun ContentBlock.renderLinked(node: DocumentationNode, body: ContentBlock.(DocumentationNode)->Unit) {
        val to = node.links.firstOrNull()
        if (to == null)
            body(node)
        else
            link(to) {
                body(node)
            }
    }

    private fun ContentBlock.renderType(node: DocumentationNode, renderMode: RenderMode) {
        var typeArguments = node.details(DocumentationNode.Kind.Type)
        if (node.name == "Function${typeArguments.count() - 1}") {
            // lambda
            val isExtension = node.annotations.any { it.name == "Extension" }
            if (isExtension) {
                renderType(typeArguments.first(), renderMode)
                symbol(".")
                typeArguments = typeArguments.drop(1)
            }
            symbol("(")
            renderList(typeArguments.take(typeArguments.size - 1), noWrap = true) {
                renderType(it, renderMode)
            }
            symbol(")")
            nbsp()
            symbol("->")
            nbsp()
            renderType(typeArguments.last(), renderMode)
            return
        }
        if (renderMode == RenderMode.FULL) {
            renderAnnotationsForNode(node)
        }
        renderModifiersForNode(node, renderMode, true)
        renderLinked(node) { identifier(it.name, IdentifierKind.TypeName) }
        if (typeArguments.any()) {
            symbol("<")
            renderList(typeArguments, noWrap = true) {
                renderType(it, renderMode)
            }
            symbol(">")
        }
        val nullabilityModifier = node.details(DocumentationNode.Kind.NullabilityModifier).singleOrNull()
        if (nullabilityModifier != null) {
            symbol(nullabilityModifier.name)
        }
    }

    private fun ContentBlock.renderModifier(node: DocumentationNode, nowrap: Boolean = false) {
        when (node.name) {
            "final", "public", "var" -> {}
            else -> {
                keyword(node.name)
                if (nowrap) {
                    nbsp()
                }
                else {
                    text(" ")
                }
            }
        }
    }

    private fun ContentBlock.renderTypeParameter(node: DocumentationNode, renderMode: RenderMode) {
        renderModifiersForNode(node, renderMode, true)

        identifier(node.name)

        val constraints = node.details(DocumentationNode.Kind.UpperBound)
        if (constraints.any()) {
            nbsp()
            symbol(":")
            nbsp()
            renderList(constraints, noWrap=true) {
                renderType(it, renderMode)
            }
        }
    }
    private fun ContentBlock.renderParameter(node: DocumentationNode, renderMode: RenderMode) {
        if (renderMode == RenderMode.FULL) {
            renderAnnotationsForNode(node)
        }
        renderModifiersForNode(node, renderMode)
        identifier(node.name, IdentifierKind.ParameterName)
        symbol(":")
        nbsp()
        val parameterType = node.detail(DocumentationNode.Kind.Type)
        renderType(parameterType, renderMode)
        val valueNode = node.details(DocumentationNode.Kind.Value).firstOrNull()
        if (valueNode != null) {
            nbsp()
            symbol("=")
            nbsp()
            text(valueNode.name)
        }
    }

    private fun ContentBlock.renderTypeParametersForNode(node: DocumentationNode, renderMode: RenderMode) {
        val typeParameters = node.details(DocumentationNode.Kind.TypeParameter)
        if (typeParameters.any()) {
            symbol("<")
            renderList(typeParameters) {
                renderTypeParameter(it, renderMode)
            }
            symbol(">")
        }
    }

    private fun ContentBlock.renderSupertypesForNode(node: DocumentationNode, renderMode: RenderMode) {
        val supertypes = node.details(DocumentationNode.Kind.Supertype)
        if (supertypes.any()) {
            nbsp()
            symbol(":")
            nbsp()
            renderList(supertypes) {
                indentedSoftLineBreak()
                renderType(it, renderMode)
            }
        }
    }

    private fun ContentBlock.renderModifiersForNode(node: DocumentationNode,
                                                    renderMode: RenderMode,
                                                    nowrap: Boolean = false) {
        val modifiers = node.details(DocumentationNode.Kind.Modifier)
        for (it in modifiers) {
            if (node.kind == org.jetbrains.dokka.DocumentationNode.Kind.Interface && it.name == "abstract")
                continue
            if (renderMode == RenderMode.SUMMARY && it.name in fullOnlyModifiers) {
                continue
            }
            renderModifier(it, nowrap)
        }
    }

    private fun ContentBlock.renderAnnotationsForNode(node: DocumentationNode) {
        node.annotations.forEach {
            renderAnnotation(it)
        }
    }

    private fun ContentBlock.renderAnnotation(node: DocumentationNode) {
        identifier("@" + node.name, IdentifierKind.AnnotationName)
        val parameters = node.details(DocumentationNode.Kind.Parameter)
        if (!parameters.isEmpty()) {
            symbol("(")
            renderList(parameters) {
                text(it.detail(DocumentationNode.Kind.Value).name)
            }
            symbol(")")
        }
        text(" ")
    }

    private fun ContentBlock.renderClass(node: DocumentationNode, renderMode: RenderMode) {
        if (renderMode == RenderMode.FULL) {
            renderAnnotationsForNode(node)
        }
        renderModifiersForNode(node, renderMode)
        when (node.kind) {
            DocumentationNode.Kind.Class,
            DocumentationNode.Kind.AnnotationClass,
            DocumentationNode.Kind.Enum -> keyword("class ")
            DocumentationNode.Kind.Interface -> keyword("interface ")
            DocumentationNode.Kind.EnumItem -> keyword("enum val ")
            DocumentationNode.Kind.Object -> keyword("object ")
            else -> throw IllegalArgumentException("Node $node is not a class-like object")
        }

        identifierOrDeprecated(node)
        renderTypeParametersForNode(node, renderMode)
        renderSupertypesForNode(node, renderMode)
    }

    private fun ContentBlock.renderFunction(node: DocumentationNode,
                                            renderMode: RenderMode,
                                            signatureMapper: SignatureMapper? = null) {
        if (renderMode == RenderMode.FULL) {
            renderAnnotationsForNode(node)
        }
        renderModifiersForNode(node, renderMode)
        when (node.kind) {
            DocumentationNode.Kind.Constructor -> identifier(node.owner!!.name)
            DocumentationNode.Kind.Function,
            DocumentationNode.Kind.CompanionObjectFunction -> keyword("fun ")
            else -> throw IllegalArgumentException("Node $node is not a function-like object")
        }
        renderTypeParametersForNode(node, renderMode)
        if (node.details(DocumentationNode.Kind.TypeParameter).any()) {
            text(" ")
        }

        renderReceiver(node, renderMode, signatureMapper)

        if (node.kind != org.jetbrains.dokka.DocumentationNode.Kind.Constructor)
            identifierOrDeprecated(node)

        symbol("(")
        val parameters = node.details(DocumentationNode.Kind.Parameter)
        renderList(parameters) {
            indentedSoftLineBreak()
            renderParameter(it, renderMode)
        }
        if (needReturnType(node)) {
            if (parameters.isNotEmpty()) {
                softLineBreak()
            }
            symbol(")")
            symbol(": ")
            renderType(node.detail(DocumentationNode.Kind.Type), renderMode)
        }
        else {
            symbol(")")
        }
    }

    private fun ContentBlock.renderReceiver(node: DocumentationNode, renderMode: RenderMode, signatureMapper: SignatureMapper?) {
        val receiver = node.details(DocumentationNode.Kind.Receiver).singleOrNull()
        if (receiver != null) {
            if (signatureMapper != null) {
                signatureMapper.renderReceiver(receiver, this)
            } else {
                renderType(receiver.detail(DocumentationNode.Kind.Type), renderMode)
            }
            symbol(".")
        }
    }

    private fun needReturnType(node: DocumentationNode) = when(node.kind) {
        DocumentationNode.Kind.Constructor -> false
        else -> !node.isUnitReturnType()
    }

    fun DocumentationNode.isUnitReturnType(): Boolean =
            detail(DocumentationNode.Kind.Type).hiddenLinks.firstOrNull()?.qualifiedName() == "kotlin.Unit"

    private fun ContentBlock.renderProperty(node: DocumentationNode,
                                            renderMode: RenderMode,
                                            signatureMapper: SignatureMapper? = null) {
        if (renderMode == RenderMode.FULL) {
            renderAnnotationsForNode(node)
        }
        renderModifiersForNode(node, renderMode)
        when (node.kind) {
            DocumentationNode.Kind.Property,
            DocumentationNode.Kind.CompanionObjectProperty -> keyword("${node.getPropertyKeyword()} ")
            else -> throw IllegalArgumentException("Node $node is not a property")
        }
        renderTypeParametersForNode(node, renderMode)
        if (node.details(DocumentationNode.Kind.TypeParameter).any()) {
            text(" ")
        }

        renderReceiver(node, renderMode, signatureMapper)

        identifierOrDeprecated(node)
        symbol(": ")
        renderType(node.detail(DocumentationNode.Kind.Type), renderMode)
    }

    fun DocumentationNode.getPropertyKeyword() =
            if (details(DocumentationNode.Kind.Modifier).any { it.name == "var" }) "var" else "val"

    fun ContentBlock.identifierOrDeprecated(node: DocumentationNode) {
        if (node.deprecation != null) {
            val strike = ContentStrikethrough()
            strike.identifier(node.name)
            append(strike)
        } else {
            identifier(node.name)
        }
    }
}

fun DocumentationNode.qualifiedNameFromType() = (links.firstOrNull() ?: hiddenLinks.firstOrNull())?.qualifiedName() ?: name
