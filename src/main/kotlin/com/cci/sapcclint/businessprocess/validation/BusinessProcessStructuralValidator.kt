package com.cci.sapcclint.businessprocess.validation

import com.cci.sapcclint.itemsxml.model.SourcePosition
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

class BusinessProcessStructuralValidator(
    private val inputFactory: XMLInputFactory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
    },
) {

    fun validate(path: Path): List<BusinessProcessStructureIssue> {
        val root = parseTree(path) ?: return emptyList()
        if (root.name != "process") {
            return emptyList()
        }

        return buildList {
            addAll(
                validateNode(
                    root,
                    allowedAttributes = setOf("name", "start", "onError", "processClass", "defaultNodeGroup"),
                    allowedChildren = setOf("contextParameter", "action", "scriptAction", "split", "wait", "end", "join", "notify"),
                    requiredAttributes = setOf("name", "start"),
                )
            )

            root.children.forEach { child ->
                when (child.name) {
                    "contextParameter" -> addAll(
                        validateNode(
                            child,
                            allowedAttributes = setOf("name", "use", "type"),
                            allowedChildren = emptySet(),
                            requiredAttributes = setOf("name", "type"),
                        )
                    )

                    "action" -> addAll(validateAction(child))
                    "scriptAction" -> addAll(validateScriptAction(child))
                    "split" -> addAll(validateSplit(child))
                    "wait" -> addAll(validateWait(child))
                    "end" -> addAll(validateEnd(child))
                    "join" -> addAll(validateJoin(child))
                    "notify" -> addAll(validateNotify(child))
                }
            }
        }
    }

    private fun validateAction(node: XmlNode): List<BusinessProcessStructureIssue> {
        return buildList {
            addAll(
                validateNode(
                    node,
                    allowedAttributes = setOf("id", "bean", "node", "nodeGroup", "canJoinPreviousNode"),
                    allowedChildren = setOf("parameter", "transition"),
                    requiredAttributes = setOf("id", "bean"),
                    requiredChildren = setOf("transition"),
                )
            )
            node.children.filter { it.name == "parameter" }.forEach { addAll(validateParameter(it)) }
            node.children.filter { it.name == "transition" }.forEach { addAll(validateTransition(it)) }
        }
    }

    private fun validateScriptAction(node: XmlNode): List<BusinessProcessStructureIssue> {
        return buildList {
            addAll(
                validateNode(
                    node,
                    allowedAttributes = setOf("id", "node", "nodeGroup", "canJoinPreviousNode"),
                    allowedChildren = setOf("script", "parameter", "transition"),
                    requiredAttributes = setOf("id"),
                    requiredChildren = setOf("script", "transition"),
                )
            )
            node.children.filter { it.name == "script" }.forEach { addAll(validateScript(it)) }
            node.children.filter { it.name == "parameter" }.forEach { addAll(validateParameter(it)) }
            node.children.filter { it.name == "transition" }.forEach { addAll(validateTransition(it)) }
        }
    }

    private fun validateSplit(node: XmlNode): List<BusinessProcessStructureIssue> {
        return buildList {
            addAll(
                validateNode(
                    node,
                    allowedAttributes = setOf("id"),
                    allowedChildren = setOf("targetNode"),
                    requiredAttributes = setOf("id"),
                    requiredChildren = setOf("targetNode"),
                )
            )
            node.children.filter { it.name == "targetNode" }.forEach { target ->
                addAll(validateNode(target, allowedAttributes = setOf("name"), allowedChildren = emptySet(), requiredAttributes = setOf("name")))
            }
        }
    }

    private fun validateWait(node: XmlNode): List<BusinessProcessStructureIssue> {
        return buildList {
            addAll(
                validateNode(
                    node,
                    allowedAttributes = setOf("id", "then", "prependProcessCode"),
                    allowedChildren = setOf("timeout", "event", "case"),
                    requiredAttributes = setOf("id"),
                )
            )
            node.children.filter { it.name == "timeout" }.forEach { timeout ->
                addAll(validateNode(timeout, allowedAttributes = setOf("delay", "then"), allowedChildren = emptySet(), requiredAttributes = setOf("delay", "then")))
            }
            node.children.filter { it.name == "event" }.forEach { event ->
                addAll(validateNode(event, allowedAttributes = emptySet(), allowedChildren = emptySet()))
            }
            node.children.filter { it.name == "case" }.forEach { caseNode ->
                addAll(
                    validateNode(
                        caseNode,
                        allowedAttributes = setOf("event"),
                        allowedChildren = setOf("choice"),
                        requiredAttributes = setOf("event"),
                    )
                )
                caseNode.children.filter { it.name == "choice" }.forEach { choice ->
                    addAll(
                        validateNode(
                            choice,
                            allowedAttributes = setOf("id", "then"),
                            allowedChildren = emptySet(),
                            requiredAttributes = setOf("id", "then"),
                        )
                    )
                }
            }
        }
    }

    private fun validateEnd(node: XmlNode): List<BusinessProcessStructureIssue> {
        return buildList {
            addAll(
                validateNode(
                    node,
                    allowedAttributes = setOf("id", "state"),
                    allowedChildren = emptySet(),
                    requiredAttributes = setOf("id"),
                    requiresText = true,
                )
            )
        }
    }

    private fun validateJoin(node: XmlNode): List<BusinessProcessStructureIssue> {
        return buildList {
            addAll(
                validateNode(
                    node,
                    allowedAttributes = setOf("id", "then"),
                    allowedChildren = emptySet(),
                    requiredAttributes = setOf("id"),
                )
            )
        }
    }

    private fun validateNotify(node: XmlNode): List<BusinessProcessStructureIssue> {
        return buildList {
            addAll(
                validateNode(
                    node,
                    allowedAttributes = setOf("id", "then"),
                    allowedChildren = setOf("userGroup"),
                    requiredAttributes = setOf("id"),
                    requiredChildren = setOf("userGroup"),
                )
            )
            node.children.filter { it.name == "userGroup" }.forEach { userGroup ->
                addAll(
                    validateNode(
                        userGroup,
                        allowedAttributes = setOf("name", "message"),
                        allowedChildren = setOf("locmessage"),
                        requiredAttributes = setOf("name"),
                        requiredChildren = setOf("locmessage"),
                    )
                )
                userGroup.children.filter { it.name == "locmessage" }.forEach { message ->
                    addAll(
                        validateNode(
                            message,
                            allowedAttributes = setOf("name", "language"),
                            allowedChildren = emptySet(),
                            requiredAttributes = setOf("name", "language"),
                        )
                    )
                }
            }
        }
    }

    private fun validateParameter(node: XmlNode): List<BusinessProcessStructureIssue> {
        return validateNode(
            node,
            allowedAttributes = setOf("name", "value"),
            allowedChildren = emptySet(),
            requiredAttributes = setOf("name", "value"),
        )
    }

    private fun validateTransition(node: XmlNode): List<BusinessProcessStructureIssue> {
        return validateNode(
            node,
            allowedAttributes = setOf("name", "to"),
            allowedChildren = emptySet(),
            requiredAttributes = setOf("name", "to"),
        )
    }

    private fun validateScript(node: XmlNode): List<BusinessProcessStructureIssue> {
        return validateNode(
            node,
            allowedAttributes = setOf("type"),
            allowedChildren = emptySet(),
            requiredAttributes = setOf("type"),
            requiresText = true,
        )
    }

    private fun validateNode(
        node: XmlNode,
        allowedAttributes: Set<String>,
        allowedChildren: Set<String>,
        requiredAttributes: Set<String> = emptySet(),
        requiredChildren: Set<String> = emptySet(),
        requiresText: Boolean = false,
    ): List<BusinessProcessStructureIssue> {
        return buildList {
            node.attributes.keys
                .filterNot { it in allowedAttributes }
                .forEach { attributeName ->
                    add(issue("Attribute '$attributeName' is not allowed on <${node.name}>.", node.location))
                }

            requiredAttributes
                .filterNot { it in node.attributes }
                .forEach { attributeName ->
                    add(issue("Attribute '$attributeName' is required on <${node.name}>.", node.location))
                }

            node.children
                .filterNot { it.name in allowedChildren }
                .forEach { child ->
                    add(issue("Element <${child.name}> is not allowed inside <${node.name}>.", child.location))
                }

            requiredChildren
                .filterNot { requiredChild -> node.children.any { it.name == requiredChild } }
                .forEach { childName ->
                    add(issue("Element <${node.name}> requires a <$childName> child.", node.location))
                }

            if (requiresText && node.text.isBlank()) {
                add(issue("Element <${node.name}> requires text content.", node.location))
            }
        }
    }

    private fun issue(message: String, location: SourcePosition): BusinessProcessStructureIssue {
        return BusinessProcessStructureIssue(message = message, location = location)
    }

    private fun parseTree(path: Path): XmlNode? {
        return runCatching {
            Files.newInputStream(path).use { input ->
                val reader = inputFactory.createXMLStreamReader(input)
                reader.use { readTree(reader) }
            }
        }.onFailure { failure ->
            if (failure !is XMLStreamException && failure.cause !is XMLStreamException) {
                throw failure
            }
        }.getOrNull()
    }

    private fun readTree(reader: XMLStreamReader): XmlNode? {
        val state = XmlTreeState()
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> handleStartElement(reader, state)
                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> appendText(reader, state)
                XMLStreamConstants.END_ELEMENT -> popNode(state)
            }
        }
        return state.root
    }

    private fun handleStartElement(reader: XMLStreamReader, state: XmlTreeState) {
        val node = XmlNode(
            name = reader.localName,
            attributes = readAttributes(reader),
            location = reader.currentPosition(),
        )
        state.attach(node)
    }

    private fun appendText(reader: XMLStreamReader, state: XmlTreeState) {
        state.stack.lastOrNull()?.let { it.text += reader.text }
    }

    private fun popNode(state: XmlTreeState) {
        if (state.stack.isNotEmpty()) {
            state.stack.removeLast()
        }
    }

    private fun readAttributes(reader: XMLStreamReader): Map<String, String> {
        return buildMap {
            for (index in 0 until reader.attributeCount) {
                put(reader.getAttributeLocalName(index), reader.getAttributeValue(index))
            }
        }
    }

    private fun XMLStreamReader.currentPosition(): SourcePosition = SourcePosition(
        line = location.lineNumber,
        column = location.columnNumber,
    )

    private fun <T> XMLStreamReader.use(block: () -> T): T {
        return try {
            block()
        } finally {
            close()
        }
    }

    private data class XmlNode(
        val name: String,
        val attributes: Map<String, String>,
        val location: SourcePosition,
        val children: MutableList<XmlNode> = mutableListOf(),
        var text: String = "",
    )

    private data class XmlTreeState(
        val stack: ArrayDeque<XmlNode> = ArrayDeque(),
        var root: XmlNode? = null,
    ) {
        fun attach(node: XmlNode) {
            if (stack.isEmpty()) {
                root = node
            } else {
                stack.last().children += node
            }
            stack.addLast(node)
        }
    }
}

data class BusinessProcessStructureIssue(
    val message: String,
    val location: SourcePosition,
)
