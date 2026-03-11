package ai.nina.labs.sapcclint.project.parser

import ai.nina.labs.sapcclint.itemsxml.model.LocatedValue
import ai.nina.labs.sapcclint.itemsxml.model.SourcePosition
import ai.nina.labs.sapcclint.project.model.LocalExtensionDecl
import ai.nina.labs.sapcclint.project.model.ParsedExtensionInfoFile
import ai.nina.labs.sapcclint.project.model.ParsedLocalExtensionsFile
import ai.nina.labs.sapcclint.project.model.RequiredExtensionDecl
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

class ProjectDescriptorParser {

    private val inputFactory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
    }

    fun parseExtensionInfo(path: Path): ParsedExtensionInfoFile {
        val source = Files.readString(path)
        val locator = SourceLocator(source)
        val reader = inputFactory.createXMLStreamReader(StringReader(source))
        return reader.use {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == "extensioninfo") {
                    return@use parseExtensionInfo(reader, locator, path)
                }
            }
            throw IllegalArgumentException("Expected extensioninfo root element in $path")
        }
    }

    fun parseLocalExtensions(path: Path): ParsedLocalExtensionsFile {
        val source = Files.readString(path)
        val locator = SourceLocator(source)
        val reader = inputFactory.createXMLStreamReader(StringReader(source))
        return reader.use {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT && reader.localName == "hybrisconfig") {
                    return@use parseLocalExtensions(reader, locator, path)
                }
            }
            throw IllegalArgumentException("Expected hybrisconfig root element in $path")
        }
    }

    private fun parseExtensionInfo(
        reader: XMLStreamReader,
        locator: SourceLocator,
        path: Path,
    ): ParsedExtensionInfoFile {
        val location = reader.currentPosition()
        var extensionName = LocatedValue<String>(value = null, location = null)
        val requiredExtensions = mutableListOf<RequiredExtensionDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "extension" -> extensionName = parseExtension(reader, locator, requiredExtensions)
                    else -> skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "extensioninfo") {
                    return ParsedExtensionInfoFile(path, extensionName, requiredExtensions, location)
                }
            }
        }

        return ParsedExtensionInfoFile(path, extensionName, requiredExtensions, location)
    }

    private fun parseExtension(
        reader: XMLStreamReader,
        locator: SourceLocator,
        requiredExtensions: MutableList<RequiredExtensionDecl>,
    ): LocatedValue<String> {
        val name = reader.attributeText("name", locator)

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> if (reader.localName == "requires-extension") {
                    requiredExtensions += RequiredExtensionDecl(
                        name = reader.attributeText("name", locator),
                        location = reader.currentPosition(),
                    )
                    skipToEnd(reader, "requires-extension")
                } else {
                    skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "extension") {
                    return name
                }
            }
        }

        return name
    }

    private fun parseLocalExtensions(
        reader: XMLStreamReader,
        locator: SourceLocator,
        path: Path,
    ): ParsedLocalExtensionsFile {
        val location = reader.currentPosition()
        val extensions = mutableListOf<LocalExtensionDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> if (reader.localName == "extensions") {
                    extensions += parseExtensions(reader, locator)
                } else {
                    skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "hybrisconfig") {
                    return ParsedLocalExtensionsFile(path, extensions, location)
                }
            }
        }

        return ParsedLocalExtensionsFile(path, extensions, location)
    }

    private fun parseExtensions(
        reader: XMLStreamReader,
        locator: SourceLocator,
    ): List<LocalExtensionDecl> {
        val extensions = mutableListOf<LocalExtensionDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> if (reader.localName == "extension") {
                    extensions += LocalExtensionDecl(
                        name = reader.attributeText("name", locator),
                        location = reader.currentPosition(),
                    )
                    skipToEnd(reader, "extension")
                } else {
                    skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "extensions") {
                    return extensions
                }
            }
        }

        return extensions
    }

    private fun skipElement(reader: XMLStreamReader) {
        skipToEnd(reader, reader.localName)
    }

    private fun skipToEnd(reader: XMLStreamReader, elementName: String) {
        var depth = 1
        while (reader.hasNext() && depth > 0) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> depth++
                XMLStreamConstants.END_ELEMENT -> if (reader.localName == elementName || depth > 1) {
                    depth--
                }
            }
        }
    }

    private fun XMLStreamReader.currentPosition(): SourcePosition = SourcePosition(
        line = location.lineNumber,
        column = location.columnNumber,
    )

    private fun XMLStreamReader.attributeText(name: String, locator: SourceLocator): LocatedValue<String> {
        val value = (0 until attributeCount)
            .firstOrNull { getAttributeLocalName(it) == name }
            ?.let { getAttributeValue(it) }
        return LocatedValue(value, locator.findAttributePosition(currentPosition(), name))
    }

    private fun <T> XMLStreamReader.use(block: () -> T): T {
        return try {
            block()
        } finally {
            close()
        }
    }

    private class SourceLocator(private val source: String) {
        private val lineStartOffsets = buildLineStartOffsets(source)

        fun findAttributePosition(elementStart: SourcePosition, attributeName: String): SourcePosition? {
            val startOffset = tagStartOffset(elementStart)
            val tagEndOffset = source.indexOf('>', startIndex = startOffset).takeIf { it >= 0 } ?: return null
            val tagSource = source.substring(startOffset, tagEndOffset)
            val matchIndex = Regex("""\b${Regex.escape(attributeName)}\s*=""").find(tagSource)?.range?.first ?: return null
            return positionFromOffset(startOffset + matchIndex)
        }

        private fun offsetFromPosition(position: SourcePosition): Int {
            val lineOffset = lineStartOffsets.getOrNull(position.line - 1) ?: return source.length
            return (lineOffset + position.column - 1).coerceAtMost(source.length)
        }

        private fun tagStartOffset(position: SourcePosition): Int {
            val approximateOffset = offsetFromPosition(position)
            return source.lastIndexOf('<', startIndex = approximateOffset).takeIf { it >= 0 } ?: approximateOffset
        }

        private fun positionFromOffset(offset: Int): SourcePosition {
            val safeOffset = offset.coerceIn(0, source.length)
            var lineIndex = 0
            while (lineIndex + 1 < lineStartOffsets.size && lineStartOffsets[lineIndex + 1] <= safeOffset) {
                lineIndex++
            }
            return SourcePosition(
                line = lineIndex + 1,
                column = safeOffset - lineStartOffsets[lineIndex] + 1,
            )
        }

        private fun buildLineStartOffsets(text: String): List<Int> {
            val offsets = mutableListOf(0)
            text.forEachIndexed { index, character ->
                if (character == '\n' && index + 1 < text.length) {
                    offsets += index + 1
                }
            }
            return offsets
        }
    }
}
