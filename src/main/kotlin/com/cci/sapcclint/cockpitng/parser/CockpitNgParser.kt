package com.cci.sapcclint.cockpitng.parser

import com.cci.sapcclint.cockpitng.model.CockpitContextDecl
import com.cci.sapcclint.cockpitng.model.CockpitNamespaceDecl
import com.cci.sapcclint.cockpitng.model.ParsedCockpitFile
import com.cci.sapcclint.itemsxml.model.LocatedValue
import com.cci.sapcclint.itemsxml.model.SourcePosition
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

class CockpitNgParser(
    private val inputFactory: XMLInputFactory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
    },
) {

    fun parse(path: Path): ParsedCockpitFile {
        Files.newInputStream(path).use { input ->
            val reader = inputFactory.createXMLStreamReader(input)
            return reader.use {
                val state = ParserState()

                while (reader.hasNext()) {
                    when (reader.next()) {
                        XMLStreamConstants.START_ELEMENT -> handleStartElement(reader, state)
                        XMLStreamConstants.END_ELEMENT -> state.decrementDepth()
                    }
                }

                ParsedCockpitFile(
                    path = path,
                    rootTag = state.rootTag,
                    contexts = state.contexts,
                    rootNamespaces = state.rootNamespaces,
                    localNamespaces = state.localNamespaces,
                )
            }
        }
    }

    private fun handleStartElement(reader: XMLStreamReader, state: ParserState) {
        val location = reader.currentPosition()
        val elementName = reader.localName
        val isRoot = state.depth == 0
        if (isRoot) {
            state.rootTag = elementName
        }

        val namespaces = readNamespaces(reader, elementName, location)
        state.addNamespaces(namespaces, isRoot)
        if (state.rootTag == "config" && elementName == "context") {
            state.contexts += CockpitContextDecl(
                attributes = readAttributes(reader),
                location = location,
            )
        }

        state.depth++
    }

    private fun readNamespaces(
        reader: XMLStreamReader,
        elementName: String,
        location: SourcePosition,
    ): List<CockpitNamespaceDecl> {
        return buildList {
            for (index in 0 until reader.namespaceCount) {
                val prefix = reader.getNamespacePrefix(index) ?: continue
                val uri = reader.getNamespaceURI(index) ?: continue
                add(
                    CockpitNamespaceDecl(
                        prefix = prefix,
                        uri = uri,
                        elementName = elementName,
                        location = location,
                    )
                )
            }
        }
    }

    private fun readAttributes(reader: XMLStreamReader): Map<String, LocatedValue<String>> {
        return buildMap {
            for (index in 0 until reader.attributeCount) {
                put(
                    reader.getAttributeLocalName(index),
                    LocatedValue(
                        value = reader.getAttributeValue(index),
                        location = reader.currentPosition(),
                    )
                )
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

    private data class ParserState(
        var rootTag: String? = null,
        val contexts: MutableList<CockpitContextDecl> = mutableListOf(),
        val rootNamespaces: MutableList<CockpitNamespaceDecl> = mutableListOf(),
        val localNamespaces: MutableList<CockpitNamespaceDecl> = mutableListOf(),
        var depth: Int = 0,
    ) {
        fun addNamespaces(namespaces: List<CockpitNamespaceDecl>, isRoot: Boolean) {
            if (isRoot) {
                rootNamespaces += namespaces
            } else {
                localNamespaces += namespaces
            }
        }

        fun decrementDepth() {
            if (depth > 0) {
                depth--
            }
        }
    }
}
