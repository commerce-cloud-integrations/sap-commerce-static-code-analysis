package ai.nina.labs.sapcclint.beansystem.validation

import ai.nina.labs.sapcclint.itemsxml.model.SourcePosition
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

class BeanSystemStructuralValidator(
    private val inputFactory: XMLInputFactory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
    },
) {

    fun validate(path: Path): List<BeanStructureIssue> {
        val state = ValidationState()

        runCatching {
            Files.newInputStream(path).use { input ->
                val reader = inputFactory.createXMLStreamReader(input)
                reader.use { validateDocument(reader, state) }
            }
        }.onFailure { failure ->
            if (failure !is XMLStreamException && failure.cause !is XMLStreamException) {
                throw failure
            }
        }

        if (!state.sawRoot) {
            state.issues += BeanStructureIssue(
                message = "Root element must be <${beansSpec.name}>.",
                location = SourcePosition(1, 1),
            )
        }

        return state.issues
    }

    private fun validateDocument(reader: XMLStreamReader, state: ValidationState) {
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> handleStartElement(reader, state)
                XMLStreamConstants.END_ELEMENT -> handleEndElement(state)
            }
        }
    }

    private fun handleStartElement(reader: XMLStreamReader, state: ValidationState) {
        val name = reader.localName
        val location = reader.currentPosition()
        if (!state.sawRoot) {
            validateRootElement(reader, state, name, location)
            return
        }

        val parent = state.stack.lastOrNull() ?: return
        parent.increment(name)
        val spec = elementSpecs[name]
        if (name !in parent.spec.allowedChildren || spec == null) {
            state.issues += BeanStructureIssue(
                message = "Element <$name> is not allowed inside <${parent.spec.name}>.",
                location = location,
            )
            skipElement(reader)
            return
        }

        state.issues += validateElement(reader, spec, location)
        state.stack.addLast(ElementFrame(spec, location))
    }

    private fun validateRootElement(
        reader: XMLStreamReader,
        state: ValidationState,
        name: String,
        location: SourcePosition,
    ) {
        state.sawRoot = true
        if (name != beansSpec.name) {
            state.issues += BeanStructureIssue(
                message = "Root element must be <${beansSpec.name}>.",
                location = location,
            )
            skipElement(reader)
            return
        }

        state.issues += validateElement(reader, beansSpec, location)
        state.stack.addLast(ElementFrame(beansSpec, location))
    }

    private fun handleEndElement(state: ValidationState) {
        if (state.stack.isEmpty()) {
            return
        }
        val frame = state.stack.removeLast()
        state.issues += validateCompletedElement(frame)
    }

    private fun validateElement(
        reader: XMLStreamReader,
        spec: ElementSpec,
        location: SourcePosition,
    ): List<BeanStructureIssue> {
        val issues = mutableListOf<BeanStructureIssue>()
        val presentAttributes = linkedSetOf<String>()

        for (index in 0 until reader.attributeCount) {
            val attributeName = reader.getAttributeLocalName(index)
            presentAttributes += attributeName
            if (attributeName !in spec.allowedAttributes) {
                issues += BeanStructureIssue(
                    message = "Attribute '$attributeName' is not allowed on <${spec.name}>.",
                    location = location,
                )
                continue
            }

            val allowedValues = spec.enumAttributes[attributeName] ?: continue
            val attributeValue = reader.getAttributeValue(index)
            if (attributeValue !in allowedValues) {
                issues += BeanStructureIssue(
                    message = "Attribute '$attributeName' on <${spec.name}> must be one of: ${allowedValues.joinToString(", ")}.",
                    location = location,
                )
            }
        }

        spec.requiredAttributes
            .filterNot(presentAttributes::contains)
            .forEach { attributeName ->
                issues += BeanStructureIssue(
                    message = "Attribute '$attributeName' is required on <${spec.name}>.",
                    location = location,
                )
            }

        return issues
    }

    private fun validateCompletedElement(frame: ElementFrame): List<BeanStructureIssue> {
        val issues = mutableListOf<BeanStructureIssue>()

        frame.spec.requiredChildren.forEach { (childName, minimum) ->
            if (frame.childCounts[childName] ?: 0 < minimum) {
                issues += BeanStructureIssue(
                    message = "Element <${frame.spec.name}> requires at least $minimum <$childName> child${if (minimum == 1) "" else "ren"}.",
                    location = frame.location,
                )
            }
        }

        frame.spec.singletonChildren.forEach { childName ->
            if ((frame.childCounts[childName] ?: 0) > 1) {
                issues += BeanStructureIssue(
                    message = "Element <$childName> may appear only once inside <${frame.spec.name}>.",
                    location = frame.location,
                )
            }
        }

        return issues
    }

    private fun skipElement(reader: XMLStreamReader) {
        var depth = 1
        while (reader.hasNext() && depth > 0) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> depth++
                XMLStreamConstants.END_ELEMENT -> depth--
            }
        }
    }

    private fun XMLStreamReader.currentPosition(): SourcePosition = SourcePosition(
        line = location.lineNumber,
        column = location.columnNumber,
    )

    private fun XMLStreamReader.use(block: () -> Unit) {
        try {
            block()
        } finally {
            close()
        }
    }

    private data class ElementFrame(
        val spec: ElementSpec,
        val location: SourcePosition,
        val childCounts: MutableMap<String, Int> = linkedMapOf(),
    ) {
        fun increment(childName: String) {
            childCounts[childName] = (childCounts[childName] ?: 0) + 1
        }
    }

    private data class ValidationState(
        val issues: MutableList<BeanStructureIssue> = mutableListOf(),
        val stack: ArrayDeque<ElementFrame> = ArrayDeque(),
        var sawRoot: Boolean = false,
    )

    private data class ElementSpec(
        val name: String,
        val allowedChildren: Set<String>,
        val allowedAttributes: Set<String>,
        val requiredAttributes: Set<String> = emptySet(),
        val requiredChildren: Map<String, Int> = emptyMap(),
        val singletonChildren: Set<String> = emptySet(),
        val enumAttributes: Map<String, Set<String>> = emptyMap(),
    )

    companion object {
        private val beansSpec = ElementSpec(
            name = "beans",
            allowedChildren = setOf("bean", "enum"),
            allowedAttributes = emptySet(),
        )
        private val beanSpec = ElementSpec(
            name = "bean",
            allowedChildren = setOf("hints", "description", "import", "annotations", "property"),
            allowedAttributes = setOf("class", "extends", "type", "deprecated", "deprecatedSince", "abstract", "superEquals", "template"),
            requiredAttributes = setOf("class"),
            singletonChildren = setOf("hints", "description"),
            enumAttributes = mapOf("type" to setOf("bean", "event")),
        )
        private val enumSpec = ElementSpec(
            name = "enum",
            allowedChildren = setOf("description", "value"),
            allowedAttributes = setOf("class", "deprecated", "deprecatedSince", "template"),
            requiredAttributes = setOf("class"),
            requiredChildren = mapOf("value" to 1),
            singletonChildren = setOf("description"),
        )
        private val propertySpec = ElementSpec(
            name = "property",
            allowedChildren = setOf("description", "annotations", "hints"),
            allowedAttributes = setOf("name", "type", "equals", "deprecated"),
            requiredAttributes = setOf("name", "type"),
            singletonChildren = setOf("description", "hints"),
        )
        private val importSpec = ElementSpec(
            name = "import",
            allowedChildren = emptySet(),
            allowedAttributes = setOf("type", "static"),
            requiredAttributes = setOf("type"),
        )
        private val annotationsSpec = ElementSpec(
            name = "annotations",
            allowedChildren = emptySet(),
            allowedAttributes = setOf("scope"),
            enumAttributes = mapOf("scope" to setOf("all", "getter", "member", "setter")),
        )
        private val hintsSpec = ElementSpec(
            name = "hints",
            allowedChildren = setOf("hint"),
            allowedAttributes = emptySet(),
        )
        private val hintSpec = ElementSpec(
            name = "hint",
            allowedChildren = emptySet(),
            allowedAttributes = setOf("name"),
            requiredAttributes = setOf("name"),
        )
        private val descriptionSpec = ElementSpec(
            name = "description",
            allowedChildren = emptySet(),
            allowedAttributes = emptySet(),
        )
        private val valueSpec = ElementSpec(
            name = "value",
            allowedChildren = emptySet(),
            allowedAttributes = emptySet(),
        )
        private val elementSpecs = listOf(
            beansSpec,
            beanSpec,
            enumSpec,
            propertySpec,
            importSpec,
            annotationsSpec,
            hintsSpec,
            hintSpec,
            descriptionSpec,
            valueSpec,
        ).associateBy(ElementSpec::name)
    }
}

data class BeanStructureIssue(
    val message: String,
    val location: SourcePosition,
)
