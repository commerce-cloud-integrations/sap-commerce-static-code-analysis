package com.cci.sapcclint.cockpitng.validation

import com.cci.sapcclint.itemsxml.model.SourcePosition
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

private const val MODULE_URL_ATTRIBUTE = "module-url"
private const val PRIMARY_ACTION_ELEMENT = "primary-action"

class CockpitNgStructuralValidator(
    private val inputFactory: XMLInputFactory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
    },
) {

    fun validate(path: Path): List<CockpitNgStructureIssue> {
        val root = parseTree(path) ?: return emptyList()
        return when (root.name) {
            "config" -> validateConfig(root)
            "widgets" -> validateWidgets(root)
            else -> emptyList()
        }
    }

    private fun validateConfig(root: XmlNode): List<CockpitNgStructureIssue> {
        return buildList {
            addAll(validateNode(root, configRuleId, setOf("required-parameters"), setOf("requires", "context", "import")))

            root.children.filter { it.name == "requires" }.forEach { requires ->
                addAll(validateNode(requires, configRuleId, setOf("resource"), emptySet(), requiredAttributes = setOf("resource")))
            }

            root.children.filter { it.name == "import" }.forEach { importNode ->
                addAll(validateNode(importNode, configRuleId, setOf("resource", MODULE_URL_ATTRIBUTE), emptySet(), requiredAttributes = setOf("resource")))
            }

            root.children.filter { it.name == "context" }.forEach { context ->
                addAll(
                    validateNode(
                        context,
                        configRuleId,
                        allowedAttributes = setOf("merge-by", "type", "principal", "component", "authority", "parent"),
                        allowedChildren = contextChildren,
                    )
                )
            }

            root.descendants("actions").forEach { actions ->
                addAll(validateActions(actions))
            }
        }
    }

    private fun validateActions(actions: XmlNode): List<CockpitNgStructureIssue> {
        return buildList {
            addAll(validateNode(actions, actionsRuleId, emptySet(), actionGroupChildren))
            actions.children.forEach { group ->
                when (group.name) {
                    "group", "three-dots-group" -> addAll(validateActionGroup(group, setOf("label", "action")))
                    "extended-group" -> addAll(validateActionGroup(group, setOf("label", "action", "extended-action")))
                    "split-group" -> addAll(validateActionGroup(group, setOf("label", "action", PRIMARY_ACTION_ELEMENT), requiredChildren = setOf(PRIMARY_ACTION_ELEMENT)))
                }
            }
        }
    }

    private fun validateActionGroup(
        group: XmlNode,
        allowedChildren: Set<String>,
        requiredChildren: Set<String> = emptySet(),
    ): List<CockpitNgStructureIssue> {
        return buildList {
            addAll(
                validateNode(
                    group,
                    actionsRuleId,
                    allowedAttributes = groupAttributes,
                    allowedChildren = allowedChildren,
                    requiredChildren = requiredChildren,
                )
            )

            group.children.forEach { child ->
                when (child.name) {
                    "action", "extended-action", PRIMARY_ACTION_ELEMENT -> addAll(validateAction(child))
                    "label" -> addAll(validateNode(child, actionsRuleId, emptySet(), emptySet()))
                }
            }
        }
    }

    private fun validateAction(action: XmlNode): List<CockpitNgStructureIssue> {
        return buildList {
            addAll(validateNode(action, actionsRuleId, actionAttributes, setOf("parameter")))
            action.children.filter { it.name == "parameter" }.forEach { parameter ->
                addAll(
                    validateNode(
                        parameter,
                        actionsRuleId,
                        allowedAttributes = emptySet(),
                        allowedChildren = setOf("name", "value"),
                        requiredChildren = setOf("name", "value"),
                    )
                )
                parameter.children.forEach { child ->
                    if (child.name == "name" || child.name == "value") {
                        addAll(validateNode(child, actionsRuleId, emptySet(), emptySet()))
                    }
                }
            }
        }
    }

    private fun validateWidgets(root: XmlNode): List<CockpitNgStructureIssue> {
        return buildList {
            addAll(validateNode(root, widgetsRuleId, setOf("required-parameters"), widgetsChildren))

            root.children.filter { it.name == "requires" }.forEach { requires ->
                addAll(validateNode(requires, widgetsRuleId, setOf("resource"), emptySet(), requiredAttributes = setOf("resource")))
            }
            root.children.filter { it.name == "import" }.forEach { importNode ->
                addAll(validateNode(importNode, widgetsRuleId, setOf("resource", MODULE_URL_ATTRIBUTE), emptySet(), requiredAttributes = setOf("resource")))
            }
            root.children.filter { it.name == "widget" }.forEach { widget ->
                addAll(validateWidget(widget))
            }
            root.children.filter { it.name == "widget-extension" }.forEach { extension ->
                addAll(validateWidgetExtension(extension))
            }
            root.children.filter { it.name == "widget-connection" }.forEach { connection ->
                addAll(
                    validateNode(
                        connection,
                        widgetsRuleId,
                        allowedAttributes = setOf("name", "sourceWidgetId", "outputId", "targetWidgetId", "inputId", MODULE_URL_ATTRIBUTE),
                        allowedChildren = emptySet(),
                        requiredAttributes = setOf("sourceWidgetId", "outputId", "targetWidgetId", "inputId"),
                    )
                )
            }
            root.children.filter { it.name == "widget-connection-remove" }.forEach { connection ->
                addAll(
                    validateNode(
                        connection,
                        widgetsRuleId,
                        allowedAttributes = setOf("name", "sourceWidgetId", "outputId", "targetWidgetId", "inputId"),
                        allowedChildren = emptySet(),
                    )
                )
            }
        }
    }

    private fun validateWidget(widget: XmlNode): List<CockpitNgStructureIssue> {
        return buildList {
            addAll(
                validateNode(
                    widget,
                    widgetsRuleId,
                    allowedAttributes = setOf(
                        "id",
                        "widgetDefinitionId",
                        "slotId",
                        "title",
                        "template",
                        "lastFocusedChildIndex",
                        "lastFocusedTemplateInstanceId",
                        "access",
                    ),
                    allowedChildren = setOf("widget", "instance-settings", "setting", "virtual-sockets"),
                    requiredAttributes = setOf("id", "widgetDefinitionId"),
                )
            )
            widget.children.filter { it.name == "widget" }.forEach { child ->
                addAll(validateWidget(child))
            }
        }
    }

    private fun validateWidgetExtension(extension: XmlNode): List<CockpitNgStructureIssue> {
        return buildList {
            addAll(
                validateNode(
                    extension,
                    widgetsRuleId,
                    allowedAttributes = setOf("widgetId", "contextId"),
                    allowedChildren = setOf("move", "remove", "remove-all", "widget", "instance-settings", "setting", "access", "virtual-sockets"),
                    requiredAttributes = setOf("widgetId"),
                )
            )
            extension.children.filter { it.name == "widget" }.forEach { child ->
                addAll(validateWidget(child))
            }
        }
    }

    private fun validateNode(
        node: XmlNode,
        ruleId: String,
        allowedAttributes: Set<String>,
        allowedChildren: Set<String>,
        requiredAttributes: Set<String> = emptySet(),
        requiredChildren: Set<String> = emptySet(),
    ): List<CockpitNgStructureIssue> {
        return buildList {
            node.attributes.keys
                .filterNot { it in allowedAttributes }
                .forEach { attributeName ->
                    add(issue(ruleId, "Attribute '$attributeName' is not allowed on <${node.name}>.", node.location))
                }

            requiredAttributes
                .filterNot { it in node.attributes }
                .forEach { attributeName ->
                    add(issue(ruleId, "Attribute '$attributeName' is required on <${node.name}>.", node.location))
                }

            node.children
                .filterNot { it.name in allowedChildren }
                .forEach { child ->
                    add(issue(ruleId, "Element <${child.name}> is not allowed inside <${node.name}>.", child.location))
                }

            requiredChildren
                .filterNot { requiredChild -> node.children.any { it.name == requiredChild } }
                .forEach { childName ->
                    add(issue(ruleId, "Element <${node.name}> requires a <$childName> child.", node.location))
                }
        }
    }

    private fun issue(ruleId: String, message: String, location: SourcePosition): CockpitNgStructureIssue {
        return CockpitNgStructureIssue(
            ruleId = ruleId,
            message = message,
            location = location,
        )
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
                XMLStreamConstants.START_ELEMENT -> state.attach(createNode(reader))
                XMLStreamConstants.END_ELEMENT -> state.pop()
            }
        }
        return state.root
    }

    private fun createNode(reader: XMLStreamReader): XmlNode {
        return XmlNode(
            name = reader.localName,
            attributes = buildMap {
                for (index in 0 until reader.attributeCount) {
                    put(reader.getAttributeLocalName(index), reader.getAttributeValue(index))
                }
            },
            location = reader.currentPosition(),
            children = mutableListOf(),
        )
    }

    private fun XmlNode.descendants(name: String): List<XmlNode> {
        return buildList {
            children.forEach { child ->
                if (child.name == name) {
                    add(child)
                }
                addAll(child.descendants(name))
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
        val children: MutableList<XmlNode>,
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

        fun pop() {
            if (stack.isNotEmpty()) {
                stack.removeLast()
            }
        }
    }

    companion object {
        private const val configRuleId = "CngConfigDomElementsInspection"
        private const val actionsRuleId = "CngActionsDomElementsInspection"
        private const val widgetsRuleId = "CngWidgetsDomElementsInspection"

        private val contextChildren = setOf(
            "advanced-search",
            "cockpit-locales",
            "collection-browser",
            "dashboard",
            "explorer-tree",
            "extended-split-layout",
            "fulltext-search",
            "links",
            "notification-area",
            "perspective-chooser",
            "facet-config",
            "simple-list",
            "simple-search",
            "view-switcher",
            "flow",
            "actions",
            "editors",
            "base",
            "compare-view",
            "editorArea",
            "list-view",
            "grid-view",
            "summary-view",
            "quick-list",
        )
        private val actionGroupChildren = setOf("extended-group", "split-group", "three-dots-group", "group")
        private val groupAttributes = setOf("qualifier", "show-group-header", "show-separator", "position", "merge-mode")
        private val actionAttributes = setOf("id", "action-id", "property", "output-property", "triggerOnKeys", "position", "merge-mode")
        private val widgetsChildren = setOf("requires", "widget", "import", "widget-extension", "widget-connection", "widget-connection-remove")
    }
}

data class CockpitNgStructureIssue(
    val ruleId: String,
    val message: String,
    val location: SourcePosition,
)
