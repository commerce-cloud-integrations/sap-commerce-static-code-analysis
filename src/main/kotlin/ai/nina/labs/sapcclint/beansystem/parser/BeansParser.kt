package ai.nina.labs.sapcclint.beansystem.parser

import ai.nina.labs.sapcclint.beansystem.model.BeanDecl
import ai.nina.labs.sapcclint.beansystem.model.BeanLocatedText
import ai.nina.labs.sapcclint.beansystem.model.BeanPropertyDecl
import ai.nina.labs.sapcclint.beansystem.model.EnumDecl
import ai.nina.labs.sapcclint.beansystem.model.EnumValueDecl
import ai.nina.labs.sapcclint.beansystem.model.ParsedBeansFile
import ai.nina.labs.sapcclint.itemsxml.model.SourcePosition
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

class BeansParser {

    private val inputFactory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
    }

    fun parse(path: Path): ParsedBeansFile {
        val source = Files.readString(path)
        return runCatching { parseXml(source, path) }
            .getOrElse { parseTolerant(source, path) }
    }

    private fun parseXml(source: String, path: Path): ParsedBeansFile {
        val sourceLocator = SourceLocator(source)
        val streamReader = inputFactory.createXMLStreamReader(StringReader(source))
        return streamReader.use {
            val beans = mutableListOf<BeanDecl>()
            val enums = mutableListOf<EnumDecl>()

            while (streamReader.hasNext()) {
                if (streamReader.next() == XMLStreamConstants.START_ELEMENT && streamReader.localName == "beans") {
                    parseBeans(streamReader, sourceLocator, beans, enums)
                }
            }

            ParsedBeansFile(
                path = path,
                beans = beans,
                enums = enums,
            )
        }
    }

    private fun parseTolerant(source: String, path: Path): ParsedBeansFile {
        val sourceLocator = SourceLocator(source)
        val beans = mutableListOf<BeanDecl>()
        val enums = mutableListOf<EnumDecl>()
        var searchOffset = 0

        while (searchOffset < source.length) {
            val beanOffset = source.indexOf("<bean", searchOffset)
            val enumOffset = source.indexOf("<enum", searchOffset)
            val nextOffset = listOf(beanOffset, enumOffset).filter { it >= 0 }.minOrNull() ?: break
            if (nextOffset == beanOffset) {
                val parsed = parseTolerantBean(source, nextOffset, sourceLocator)
                if (parsed != null) {
                    beans += parsed.first
                    searchOffset = parsed.second
                } else {
                    searchOffset = nextOffset + 5
                }
            } else {
                val parsed = parseTolerantEnum(source, nextOffset, sourceLocator)
                if (parsed != null) {
                    enums += parsed.first
                    searchOffset = parsed.second
                } else {
                    searchOffset = nextOffset + 5
                }
            }
        }

        return ParsedBeansFile(
            path = path,
            beans = beans,
            enums = enums,
        )
    }

    private fun parseTolerantBean(
        source: String,
        tagStart: Int,
        sourceLocator: SourceLocator,
    ): Pair<BeanDecl, Int>? {
        val tagEnd = sourceLocator.findTagEndOffset(tagStart) ?: return null
        val tagSource = source.substring(tagStart, tagEnd + 1)
        val attributes = parseAttributes(tagSource, tagStart, sourceLocator)
        val selfClosing = tagSource.trimEnd().endsWith("/>")
        val closeTagStart = if (selfClosing) tagEnd else source.indexOf("</bean>", tagEnd).takeIf { it >= 0 } ?: return null
        val bodyStart = tagEnd + 1
        val body = if (selfClosing) "" else source.substring(bodyStart, closeTagStart)
        val properties = parseTolerantProperties(body, bodyStart, sourceLocator)

        val bean = BeanDecl(
            clazz = attributes["class"] ?: BeanLocatedText(null, null, null),
            extendsClass = attributes["extends"] ?: BeanLocatedText(null, null, null),
            type = attributes["type"] ?: BeanLocatedText(null, null, null),
            properties = properties,
            location = sourceLocator.positionFromOffset(tagStart),
        )
        return bean to if (selfClosing) tagEnd + 1 else closeTagStart + "</bean>".length
    }

    private fun parseTolerantEnum(
        source: String,
        tagStart: Int,
        sourceLocator: SourceLocator,
    ): Pair<EnumDecl, Int>? {
        val tagEnd = sourceLocator.findTagEndOffset(tagStart) ?: return null
        val tagSource = source.substring(tagStart, tagEnd + 1)
        val attributes = parseAttributes(tagSource, tagStart, sourceLocator)
        val closeTagStart = source.indexOf("</enum>", tagEnd).takeIf { it >= 0 } ?: return null
        val bodyStart = tagEnd + 1
        val body = source.substring(bodyStart, closeTagStart)
        val values = Regex("""(?s)<value>(.*?)</value>""").findAll(body).map { match ->
            val startOffset = bodyStart + match.range.first
            val valueText = match.groupValues[1].trim().takeIf { it.isNotEmpty() }
            EnumValueDecl(
                value = BeanLocatedText(
                    value = decodeXml(valueText),
                    rawValue = valueText,
                    location = sourceLocator.positionFromOffset(startOffset),
                ),
                location = sourceLocator.positionFromOffset(startOffset),
            )
        }.toList()

        val enumDecl = EnumDecl(
            clazz = attributes["class"] ?: BeanLocatedText(null, null, null),
            values = values,
            location = sourceLocator.positionFromOffset(tagStart),
        )
        return enumDecl to closeTagStart + "</enum>".length
    }

    private fun parseTolerantProperties(
        body: String,
        bodyStart: Int,
        sourceLocator: SourceLocator,
    ): List<BeanPropertyDecl> {
        val properties = mutableListOf<BeanPropertyDecl>()
        var searchOffset = 0
        while (searchOffset < body.length) {
            val propertyOffset = body.indexOf("<property", searchOffset).takeIf { it >= 0 } ?: break
            val absoluteOffset = bodyStart + propertyOffset
            val tagEnd = sourceLocator.findTagEndOffset(absoluteOffset) ?: break
            val tagSource = sourceLocator.slice(absoluteOffset, tagEnd + 1)
            val attributes = parseAttributes(tagSource, absoluteOffset, sourceLocator)
            properties += BeanPropertyDecl(
                name = attributes["name"] ?: BeanLocatedText(null, null, null),
                type = attributes["type"] ?: BeanLocatedText(null, null, null),
                location = sourceLocator.positionFromOffset(absoluteOffset),
            )
            searchOffset = propertyOffset + tagSource.length
        }
        return properties
    }

    private fun parseAttributes(
        tagSource: String,
        absoluteOffset: Int,
        sourceLocator: SourceLocator,
    ): Map<String, BeanLocatedText> {
        return attributeRegex.findAll(tagSource).associate { match ->
            val name = match.groupValues[1]
            val rawValue = match.groupValues[3]
            val offset = absoluteOffset + match.range.first
            name to BeanLocatedText(
                value = decodeXml(rawValue),
                rawValue = rawValue,
                location = sourceLocator.positionFromOffset(offset),
            )
        }
    }

    private fun decodeXml(value: String?): String? {
        return value
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.replace("&quot;", "\"")
            ?.replace("&apos;", "'")
            ?.replace("&amp;", "&")
    }

    private fun parseBeans(
        reader: XMLStreamReader,
        sourceLocator: SourceLocator,
        beans: MutableList<BeanDecl>,
        enums: MutableList<EnumDecl>,
    ) {
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "bean" -> beans += parseBean(reader, sourceLocator)
                    "enum" -> enums += parseEnum(reader, sourceLocator)
                    else -> skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "beans") {
                    return
                }
            }
        }
    }

    private fun parseBean(reader: XMLStreamReader, sourceLocator: SourceLocator): BeanDecl {
        val location = reader.currentPosition()
        val clazz = reader.attributeText("class", sourceLocator)
        val extendsClass = reader.attributeText("extends", sourceLocator)
        val type = reader.attributeText("type", sourceLocator)
        val properties = mutableListOf<BeanPropertyDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "property" -> properties += parseProperty(reader, sourceLocator)
                    else -> skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "bean") {
                    return BeanDecl(
                        clazz = clazz,
                        extendsClass = extendsClass,
                        type = type,
                        properties = properties,
                        location = location,
                    )
                }
            }
        }

        return BeanDecl(
            clazz = clazz,
            extendsClass = extendsClass,
            type = type,
            properties = properties,
            location = location,
        )
    }

    private fun parseProperty(reader: XMLStreamReader, sourceLocator: SourceLocator): BeanPropertyDecl {
        val property = BeanPropertyDecl(
            name = reader.attributeText("name", sourceLocator),
            type = reader.attributeText("type", sourceLocator),
            location = reader.currentPosition(),
        )
        skipToEnd(reader, "property")
        return property
    }

    private fun parseEnum(reader: XMLStreamReader, sourceLocator: SourceLocator): EnumDecl {
        val location = reader.currentPosition()
        val clazz = reader.attributeText("class", sourceLocator)
        val values = mutableListOf<EnumValueDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> if (reader.localName == "value") {
                    values += parseEnumValue(reader)
                } else {
                    skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "enum") {
                    return EnumDecl(
                        clazz = clazz,
                        values = values,
                        location = location,
                    )
                }
            }
        }

        return EnumDecl(
            clazz = clazz,
            values = values,
            location = location,
        )
    }

    private fun parseEnumValue(reader: XMLStreamReader): EnumValueDecl {
        val location = reader.currentPosition()
        val text = StringBuilder()
        var depth = 1

        while (reader.hasNext() && depth > 0) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    depth++
                    if (depth > 1) {
                        skipElement(reader)
                        depth--
                    }
                }

                XMLStreamConstants.CDATA, XMLStreamConstants.CHARACTERS -> if (depth == 1) {
                    text.append(reader.text)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "value") {
                    depth--
                }
            }
        }

        val value = text.toString().trim().takeIf { it.isNotEmpty() }
        return EnumValueDecl(
            value = BeanLocatedText(
                value = value,
                rawValue = value,
                location = location,
            ),
            location = location,
        )
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

    private fun XMLStreamReader.attributeText(name: String, sourceLocator: SourceLocator): BeanLocatedText {
        val value = attributeValue(name)
        return BeanLocatedText(
            value = value,
            rawValue = sourceLocator.findAttributeRawValue(currentPosition(), name),
            location = sourceLocator.findAttributePosition(currentPosition(), name),
        )
    }

    private fun XMLStreamReader.attributeValue(name: String): String? {
        val index = (0 until attributeCount).firstOrNull { getAttributeLocalName(it) == name } ?: return null
        return getAttributeValue(index)
    }

    private fun XMLStreamReader.use(block: () -> ParsedBeansFile): ParsedBeansFile {
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
            val tagEndOffset = findTagEndOffset(startOffset) ?: return null
            val tagSource = source.substring(startOffset, tagEndOffset)
            val matchIndex = Regex("""\b${Regex.escape(attributeName)}\s*=""").find(tagSource)?.range?.first ?: return null
            return positionFromOffset(startOffset + matchIndex)
        }

        fun findAttributeRawValue(elementStart: SourcePosition, attributeName: String): String? {
            val startOffset = tagStartOffset(elementStart)
            val tagEndOffset = findTagEndOffset(startOffset) ?: return null
            val tagSource = source.substring(startOffset, tagEndOffset)
            val match = Regex("""\b${Regex.escape(attributeName)}\s*=\s*(['"])(.*?)\1""").find(tagSource) ?: return null
            return match.groupValues[2]
        }

        private fun offsetFromPosition(position: SourcePosition): Int {
            val lineOffset = lineStartOffsets.getOrNull(position.line - 1) ?: return source.length
            return (lineOffset + position.column - 1).coerceAtMost(source.length)
        }

        private fun tagStartOffset(position: SourcePosition): Int {
            val approximateOffset = offsetFromPosition(position)
            return source.lastIndexOf('<', startIndex = approximateOffset).takeIf { it >= 0 } ?: approximateOffset
        }

        fun findTagEndOffset(startOffset: Int): Int? {
            var quote: Char? = null
            for (index in startOffset until source.length) {
                val character = source[index]
                if (quote != null) {
                    if (character == quote) {
                        quote = null
                    }
                    continue
                }
                if (character == '"' || character == '\'') {
                    quote = character
                } else if (character == '>') {
                    return index
                }
            }
            return null
        }

        fun slice(startOffset: Int, endExclusive: Int): String {
            return source.substring(startOffset, endExclusive.coerceAtMost(source.length))
        }

        fun positionFromOffset(offset: Int): SourcePosition {
            return positionFromOffsetInternal(offset)
        }

        private fun positionFromOffsetInternal(offset: Int): SourcePosition {
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

    companion object {
        private val attributeRegex = Regex("""([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(['"])(.*?)\2""")
    }
}
