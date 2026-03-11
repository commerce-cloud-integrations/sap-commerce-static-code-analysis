package com.cci.sapcclint.impex.parser

import com.cci.sapcclint.impex.model.ImpexHeader
import com.cci.sapcclint.impex.model.ImpexHeaderBlock
import com.cci.sapcclint.impex.model.ImpexHeaderMode
import com.cci.sapcclint.impex.model.ImpexHeaderParameter
import com.cci.sapcclint.impex.model.ImpexMacroDeclaration
import com.cci.sapcclint.impex.model.ImpexModifier
import com.cci.sapcclint.impex.model.ImpexParameter
import com.cci.sapcclint.impex.model.ImpexParameterSeparator
import com.cci.sapcclint.impex.model.ImpexReference
import com.cci.sapcclint.impex.model.ImpexReferenceKind
import com.cci.sapcclint.impex.model.ImpexValueGroup
import com.cci.sapcclint.impex.model.ImpexValueLine
import com.cci.sapcclint.impex.model.ParsedImpexFile
import com.cci.sapcclint.itemsxml.model.LocatedValue
import com.cci.sapcclint.itemsxml.model.SourcePosition
import java.nio.file.Files
import java.nio.file.Path

class ImpexParser {

    fun parse(path: Path): ParsedImpexFile {
        val source = Files.readString(path)
        val locator = SourceLocator(source)
        val logicalLines = buildLogicalLines(source)
        val macroDeclarations = mutableListOf<ImpexMacroDeclaration>()
        val headerBlocks = mutableListOf<ImpexHeaderBlockBuilder>()
        val orphanValueLines = mutableListOf<ImpexValueLine>()
        var currentHeaderBlock: ImpexHeaderBlockBuilder? = null

        logicalLines.forEach { line ->
            val trimmed = line.trimmed()
            if (trimmed.isBlank()) {
                return@forEach
            }

            when {
                isComment(trimmed) -> Unit
                isMacroDeclaration(trimmed) -> {
                    macroDeclarations += parseMacroDeclaration(line, locator)
                }

                isHeaderLine(trimmed) -> {
                    val header = parseHeader(line, locator)
                    val headerBlock = ImpexHeaderBlockBuilder(header)
                    currentHeaderBlock = headerBlock
                    headerBlocks += headerBlock
                }

                isScript(trimmed) -> Unit
                else -> {
                    val valueLine = parseValueLine(line, locator)
                    val headerBlock = currentHeaderBlock
                    if (headerBlock == null) {
                        orphanValueLines += valueLine
                    } else {
                        headerBlock.valueLines += valueLine
                    }
                }
            }
        }

        return ParsedImpexFile(
            path = path,
            macroDeclarations = macroDeclarations,
            headerBlocks = headerBlocks.map { it.build() },
            orphanValueLines = orphanValueLines,
        )
    }

    private fun parseMacroDeclaration(
        line: LogicalLine,
        locator: SourceLocator,
    ): ImpexMacroDeclaration {
        val separator = line.findTopLevelChar('=')
            ?: throw IllegalArgumentException("Invalid ImpEx macro declaration at ${line.location()}")
        val rawName = line.raw.substring(0, separator + 1)
        val rawValue = line.raw.substring(separator + 1)
        val nameText = normalize(rawName.removeSuffix("="))
        val nameOffset = line.startOffset + line.firstNonWhitespaceOffset(0)
        val valueOffset = line.startOffset + separator + 1
        return ImpexMacroDeclaration(
            name = LocatedValue(
                value = nameText,
                location = locator.positionFromOffset(nameOffset),
            ),
            rawName = rawName,
            rawValue = normalize(rawValue),
            references = extractReferences(rawValue, valueOffset, locator),
            location = line.location(),
        )
    }

    private fun parseHeader(
        line: LogicalLine,
        locator: SourceLocator,
    ): ImpexHeader {
        val segments = line.splitTopLevel(';')
        val modeSegment = segments.firstOrNull()
            ?: throw IllegalArgumentException("ImpEx header line missing mode at ${line.location()}")
        val modeText = modeSegment.text.trimStart()
        val modeToken = modeText.substringBefore(' ')
        val mode = parseHeaderMode(modeSegment.text)
        val typeRaw = modeText.substringAfter(modeToken, "").trimStart()
        val typeStart = modeSegment.absoluteOffsetOf(typeRaw)
        val parsedType = parseSegmentWithModifiers(typeRaw, typeStart, locator, ImpexReferenceKind.TYPE)
        val missingParameterSeparators = mutableListOf<SourcePosition>()
        return ImpexHeader(
            mode = mode,
            typeName = LocatedValue(parsedType.reference.text, parsedType.reference.location),
            modifiers = parsedType.modifiers,
            parameters = segments.drop(1)
                .filter { it.text.isNotBlank() }
                .map { parseHeaderParameter(it.text, it.startOffset, locator, missingParameterSeparators) },
            missingParameterSeparators = missingParameterSeparators.toList(),
            location = line.location(),
        )
    }

    private fun parseHeaderParameter(
        raw: String,
        absoluteOffset: Int,
        locator: SourceLocator,
        missingParameterSeparators: MutableList<SourcePosition>,
    ): ImpexHeaderParameter {
        val nameEnd = raw.indexOfFirstTopLevelDelimiter('(', '[').takeIf { it >= 0 } ?: raw.length
        val nameRaw = raw.substring(0, nameEnd)
        val state = SegmentState(raw, absoluteOffset, locator)
        state.index = nameEnd
        val trimmedName = normalize(nameRaw)
        val nameReference = ImpexReference(
            text = trimmedName,
            kind = classifyReference(nameRaw, trimmedName, raw.getOrNull(nameEnd)),
            location = locator.positionFromOffset(absoluteOffset + raw.firstNonWhitespaceOffset()),
        )
        val leadingParameters = state.consumeParameters(missingParameterSeparators)
        val modifiers = state.consumeModifiers()
        val trailingParameters = state.consumeParameters(missingParameterSeparators)

        return ImpexHeaderParameter(
            name = nameReference,
            leadingParameters = leadingParameters,
            modifiers = modifiers,
            trailingParameters = trailingParameters,
            location = locator.positionFromOffset(absoluteOffset + raw.firstNonWhitespaceOffset()),
        )
    }

    private fun parseParameter(
        raw: String,
        absoluteOffset: Int,
        separator: ImpexParameterSeparator?,
        locator: SourceLocator,
        missingParameterSeparators: MutableList<SourcePosition>,
    ): ImpexParameter {
        val nameEnd = raw.indexOfFirstTopLevelDelimiter('(', '[', '.').takeIf { it >= 0 } ?: raw.length
        val nameRaw = raw.substring(0, nameEnd)
        val state = SegmentState(raw, absoluteOffset, locator)
        state.index = nameEnd
        val trimmedName = normalize(nameRaw)
        val nameReference = ImpexReference(
            text = trimmedName,
            kind = classifyReference(nameRaw, trimmedName, raw.getOrNull(nameEnd)),
            location = locator.positionFromOffset(absoluteOffset + raw.firstNonWhitespaceOffset()),
        )
        val parameters = state.consumeParameters(missingParameterSeparators)
        val suffixReference = state.consumeSuffixReference()
        val modifiers = state.consumeModifiers()

        return ImpexParameter(
            name = nameReference,
            parameters = parameters,
            suffixReference = suffixReference,
            modifiers = modifiers,
            separator = separator,
            location = locator.positionFromOffset(absoluteOffset + raw.firstNonWhitespaceOffset()),
        )
    }

    private fun parseValueLine(
        line: LogicalLine,
        locator: SourceLocator,
    ): ImpexValueLine {
        val segments = line.splitTopLevel(';')
        val firstNonWhitespace = line.raw.indexOfFirst { !it.isWhitespace() }
        val startsWithSeparator = firstNonWhitespace >= 0 && line.raw[firstNonWhitespace] == ';'
        val subType = if (startsWithSeparator) {
            LocatedValue<String>(null, null)
        } else {
            val firstSegment = segments.firstOrNull()
            val value = firstSegment?.text?.takeIf { it.isNotBlank() }?.let(::normalize)
            val location = value?.let { locator.positionFromOffset(firstSegment.startOffset + firstSegment.text.firstNonWhitespaceOffset()) }
            LocatedValue(value, location)
        }
        val groupSegments = segments.drop(1)
        val valueGroups = groupSegments.mapIndexed { index, segment ->
            val normalizedValue = normalize(segment.text)
            val groupOffset = segment.startOffset + segment.text.firstNonWhitespaceOffset()
            ImpexValueGroup(
                columnIndex = index + 1,
                rawValue = normalizedValue,
                references = extractReferences(segment.text, segment.startOffset, locator),
                location = locator.positionFromOffset(groupOffset.coerceAtLeast(segment.startOffset)),
            )
        }
        return ImpexValueLine(
            subType = subType,
            valueGroups = valueGroups,
            location = line.location(),
        )
    }

    private fun parseSegmentWithModifiers(
        raw: String,
        absoluteOffset: Int,
        locator: SourceLocator,
        defaultKind: ImpexReferenceKind,
    ): SegmentParseResult {
        val nameEnd = raw.indexOfFirstTopLevelDelimiter('[').takeIf { it >= 0 } ?: raw.length
        val nameRaw = raw.substring(0, nameEnd)
        val state = SegmentState(raw, absoluteOffset, locator)
        state.index = nameEnd
        val trimmedName = normalize(nameRaw)
        return SegmentParseResult(
            reference = ImpexReference(
                text = trimmedName,
                kind = defaultKind,
                location = locator.positionFromOffset(absoluteOffset + raw.firstNonWhitespaceOffset()),
            ),
            modifiers = state.consumeModifiers(),
        )
    }

    private fun parseHeaderMode(raw: String): ImpexHeaderMode {
        val token = raw.trim().substringBefore(' ')
        return when (token) {
            "INSERT" -> ImpexHeaderMode.INSERT
            "INSERT_UPDATE" -> ImpexHeaderMode.INSERT_UPDATE
            "UPDATE" -> ImpexHeaderMode.UPDATE
            "REMOVE" -> ImpexHeaderMode.REMOVE
            else -> throw IllegalArgumentException("Unsupported ImpEx header mode '$token'")
        }
    }

    private fun classifyReference(
        rawName: String,
        normalizedName: String,
        nextDelimiter: Char?,
    ): ImpexReferenceKind {
        return when {
            normalizedName.startsWith("$") -> ImpexReferenceKind.MACRO_USAGE
            normalizedName.startsWith("&") -> ImpexReferenceKind.DOCUMENT_ID
            normalizedName.startsWith("@") -> ImpexReferenceKind.SPECIAL_PARAMETER
            nextDelimiter == '(' && rawName != rawName.trimEnd() -> ImpexReferenceKind.FUNCTION
            else -> ImpexReferenceKind.HEADER_PARAMETER
        }
    }

    private fun extractReferences(
        raw: String,
        absoluteOffset: Int,
        locator: SourceLocator,
    ): List<ImpexReference> {
        return referenceRegex.findAll(raw).map { match ->
            val kind = when {
                match.value.startsWith("$") -> ImpexReferenceKind.MACRO_USAGE
                match.value.startsWith("&") -> ImpexReferenceKind.DOCUMENT_ID
                else -> ImpexReferenceKind.HEADER_PARAMETER
            }
            ImpexReference(
                text = match.value,
                kind = kind,
                location = locator.positionFromOffset(absoluteOffset + match.range.first),
            )
        }.toList()
    }

    private fun isComment(trimmed: String): Boolean = trimmed.startsWith("#") && !trimmed.startsWith("#%")

    private fun isScript(trimmed: String): Boolean = trimmed.startsWith("#%")

    private fun isMacroDeclaration(trimmed: String): Boolean = trimmed.startsWith("$") && trimmed.findTopLevelChar('=') != null

    private fun isHeaderLine(trimmed: String): Boolean {
        return trimmed.startsWith("INSERT_UPDATE ") ||
            trimmed.startsWith("INSERT ") ||
            trimmed.startsWith("UPDATE ") ||
            trimmed.startsWith("REMOVE ")
    }

    private fun buildLogicalLines(source: String): List<LogicalLine> {
        val physicalLines = readPhysicalLines(source)
        val logicalLines = mutableListOf<LogicalLine>()
        var current = 0
        while (current < physicalLines.size) {
            val logicalLine = buildLogicalLine(source, physicalLines, current)
            logicalLines += logicalLine.line
            current = logicalLine.nextIndex
        }
        return logicalLines
    }

    private fun readPhysicalLines(source: String): List<PhysicalLine> {
        val physicalLines = mutableListOf<PhysicalLine>()
        var index = 0
        var lineNumber = 1
        while (index < source.length) {
            val lineStart = index
            while (index < source.length && source[index] != '\n' && source[index] != '\r') {
                index++
            }
            val lineEnd = index
            if (index < source.length && source[index] == '\r') {
                index++
            }
            if (index < source.length && source[index] == '\n') {
                index++
            }
            physicalLines += PhysicalLine(lineStart, lineEnd, lineNumber)
            lineNumber++
        }
        return physicalLines
    }

    private fun buildLogicalLine(
        source: String,
        physicalLines: List<PhysicalLine>,
        startIndex: Int,
    ): BuiltLogicalLine {
        val start = physicalLines[startIndex]
        var endIndex = startIndex
        while (shouldContinueLogicalLine(source, physicalLines[endIndex])) {
            if (endIndex + 1 >= physicalLines.size) {
                break
            }
            endIndex++
        }
        val end = physicalLines[endIndex]
        return BuiltLogicalLine(
            line = LogicalLine(
                raw = source.substring(start.startOffset, end.endOffset),
                startOffset = start.startOffset,
                line = start.line,
            ),
            nextIndex = endIndex + 1,
        )
    }

    private fun shouldContinueLogicalLine(source: String, physicalLine: PhysicalLine): Boolean {
        return source.substring(physicalLine.startOffset, physicalLine.endOffset).trimEnd().endsWith("\\")
    }

    private fun normalize(raw: String): String {
        return raw
            .replace(multilineContinuationRegex, "")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
    }

    private data class PhysicalLine(
        val startOffset: Int,
        val endOffset: Int,
        val line: Int,
    )

    private data class BuiltLogicalLine(
        val line: LogicalLine,
        val nextIndex: Int,
    )

    private data class LogicalLine(
        val raw: String,
        val startOffset: Int,
        val line: Int,
    ) {
        fun trimmed(): String = raw.trim()

        fun location(): SourcePosition = SourcePosition(line, raw.firstNonWhitespaceOffset() + 1)

        fun splitTopLevel(separator: Char): List<Segment> {
            val results = mutableListOf<Segment>()
            var inSingleQuote = false
            var inDoubleQuote = false
            var roundDepth = 0
            var squareDepth = 0
            var segmentStart = 0

            raw.forEachIndexed { index, character ->
                when {
                    character == '\'' && !inDoubleQuote -> inSingleQuote = !inSingleQuote
                    character == '"' && !inSingleQuote -> inDoubleQuote = !inDoubleQuote
                    !inSingleQuote && !inDoubleQuote && character == '(' -> roundDepth++
                    !inSingleQuote && !inDoubleQuote && character == ')' -> roundDepth = (roundDepth - 1).coerceAtLeast(0)
                    !inSingleQuote && !inDoubleQuote && character == '[' -> squareDepth++
                    !inSingleQuote && !inDoubleQuote && character == ']' -> squareDepth = (squareDepth - 1).coerceAtLeast(0)
                    !inSingleQuote && !inDoubleQuote && roundDepth == 0 && squareDepth == 0 && character == separator -> {
                        results += Segment(
                            text = raw.substring(segmentStart, index),
                            startOffset = startOffset + segmentStart,
                        )
                        segmentStart = index + 1
                    }
                }
            }

            results += Segment(
                text = raw.substring(segmentStart),
                startOffset = startOffset + segmentStart,
            )
            return results
        }

        fun findTopLevelChar(char: Char): Int? = raw.findTopLevelChar(char)

        fun firstNonWhitespaceOffset(from: Int = 0): Int = raw.firstNonWhitespaceOffset(from)
    }

    private data class Segment(
        val text: String,
        val startOffset: Int,
    ) {
        fun absoluteOffsetOf(part: String): Int {
            val index = text.indexOf(part)
            return if (index >= 0) startOffset + index else startOffset
        }
    }

    private data class SegmentParseResult(
        val reference: ImpexReference,
        val modifiers: List<ImpexModifier>,
    )

    private inner class SegmentState(
        private val raw: String,
        private val absoluteOffset: Int,
        private val locator: SourceLocator,
    ) {
        var index: Int = 0

        fun consumeParameters(missingParameterSeparators: MutableList<SourcePosition>): List<ImpexParameter> {
            skipWhitespace()
            if (currentChar() != '(') {
                return emptyList()
            }
            val block = consumeDelimited('(', ')') ?: return emptyList()
            val content = raw.substring(block.first + 1, block.last)
            return splitAtTopLevel(content).mapNotNull { token ->
                if (token.text.isBlank() && token.separator != null && token.separatorIndex != null) {
                    missingParameterSeparators += locator.positionFromOffset(
                        absoluteOffset + block.first + 1 + token.separatorIndex
                    )
                }
                val separator = when (token.separator) {
                    ',' -> ImpexParameterSeparator.COMMA
                    '|' -> ImpexParameterSeparator.ALTERNATIVE
                    else -> null
                }
                token.text.takeIf { it.isNotBlank() }?.let {
                    parseParameter(
                        raw = it,
                        absoluteOffset = absoluteOffset + block.first + 1 + token.startIndex,
                        separator = separator,
                        locator = locator,
                        missingParameterSeparators = missingParameterSeparators,
                    )
                }
            }
        }

        fun consumeSuffixReference(): ImpexReference? {
            skipWhitespace()
            if (currentChar() != '.' && currentChar() != '$') {
                return null
            }
            if (currentChar() == '.') {
                index++
            }
            val start = index
            while (index < raw.length && raw[index] !in charArrayOf('[', '(', ')', ',', '|') && !raw[index].isWhitespace()) {
                index++
            }
            val token = normalize(raw.substring(start, index))
            if (token.isBlank()) {
                return null
            }
            return ImpexReference(
                text = token,
                kind = classifyReference(token, token, null),
                location = locator.positionFromOffset(absoluteOffset + start),
            )
        }

        fun consumeModifiers(): List<ImpexModifier> {
            val modifiers = mutableListOf<ImpexModifier>()
            while (true) {
                skipWhitespace()
                if (currentChar() != '[') {
                    return modifiers
                }
                val block = consumeDelimited('[', ']') ?: return modifiers
                val contentStart = block.first + 1
                val content = raw.substring(contentStart, block.last)
                splitAtTopLevel(content, separators = setOf(',')).forEach { token ->
                    val entry = token.text
                    if (entry.isBlank()) {
                        return@forEach
                    }
                    val equalsIndex = entry.findTopLevelChar('=')
                    if (equalsIndex == null) {
                        return@forEach
                    }
                    val nameRaw = entry.substring(0, equalsIndex)
                    val valueRaw = entry.substring(equalsIndex + 1)
                    val nameStart = absoluteOffset + contentStart + token.startIndex + nameRaw.firstNonWhitespaceOffset()
                    val valueStart = absoluteOffset + contentStart + token.startIndex + equalsIndex + 1 + valueRaw.firstNonWhitespaceOffset()
                    modifiers += ImpexModifier(
                        name = LocatedValue(
                            value = normalize(nameRaw),
                            location = locator.positionFromOffset(nameStart),
                        ),
                        value = LocatedValue(
                            value = normalize(valueRaw),
                            location = locator.positionFromOffset(valueStart),
                        ),
                        valueQuoted = valueRaw.trimStart().startsWith("\""),
                        location = locator.positionFromOffset(absoluteOffset + block.first),
                    )
                }
            }
        }

        private fun consumeDelimited(open: Char, close: Char): IntRange? {
            skipWhitespace()
            if (currentChar() != open) {
                return null
            }
            val start = index
            var depth = 0
            var inSingleQuote = false
            var inDoubleQuote = false
            while (index < raw.length) {
                val character = raw[index]
                when {
                    character == '\'' && !inDoubleQuote -> inSingleQuote = !inSingleQuote
                    character == '"' && !inSingleQuote -> inDoubleQuote = !inDoubleQuote
                    !inSingleQuote && !inDoubleQuote && character == open -> depth++
                    !inSingleQuote && !inDoubleQuote && character == close -> {
                        depth--
                        if (depth == 0) {
                            index++
                            return start until index
                        }
                    }
                }
                index++
            }
            return null
        }

        private fun currentChar(): Char? = raw.getOrNull(index)

        private fun skipWhitespace() {
            while (index < raw.length && raw[index].isWhitespace()) {
                index++
            }
        }
    }

    private class SourceLocator(private val source: String) {
        private val lineStartOffsets = buildLineStartOffsets(source)

        fun positionFromOffset(offset: Int): SourcePosition {
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

    private data class TokenSegment(
        val text: String,
        val startIndex: Int,
        val separator: Char?,
        val separatorIndex: Int?,
    )

    private fun splitAtTopLevel(
        raw: String,
        separators: Set<Char> = setOf(',', '|'),
    ): List<TokenSegment> {
        val results = mutableListOf<TokenSegment>()
        var inSingleQuote = false
        var inDoubleQuote = false
        var roundDepth = 0
        var squareDepth = 0
        var segmentStart = 0
        var previousSeparator: Char? = null
        var previousSeparatorIndex: Int? = null

        raw.forEachIndexed { index, character ->
            when {
                character == '\'' && !inDoubleQuote -> inSingleQuote = !inSingleQuote
                character == '"' && !inSingleQuote -> inDoubleQuote = !inDoubleQuote
                !inSingleQuote && !inDoubleQuote && character == '(' -> roundDepth++
                !inSingleQuote && !inDoubleQuote && character == ')' -> roundDepth = (roundDepth - 1).coerceAtLeast(0)
                !inSingleQuote && !inDoubleQuote && character == '[' -> squareDepth++
                !inSingleQuote && !inDoubleQuote && character == ']' -> squareDepth = (squareDepth - 1).coerceAtLeast(0)
                !inSingleQuote && !inDoubleQuote && roundDepth == 0 && squareDepth == 0 && character in separators -> {
                    results += TokenSegment(
                        text = raw.substring(segmentStart, index),
                        startIndex = segmentStart,
                        separator = previousSeparator,
                        separatorIndex = previousSeparatorIndex,
                    )
                    segmentStart = index + 1
                    previousSeparator = character
                    previousSeparatorIndex = index
                }
            }
        }

        results += TokenSegment(
            text = raw.substring(segmentStart),
            startIndex = segmentStart,
            separator = previousSeparator,
            separatorIndex = previousSeparatorIndex,
        )
        return results
    }

    companion object {
        private val multilineContinuationRegex = Regex("""\\\r?\n\s*""")
        private val referenceRegex = Regex("""(\$(?:config-)?[A-Za-z0-9_.()-]+|&[A-Za-z_][A-Za-z0-9_]*)""")
    }
}

private fun String.firstNonWhitespaceOffset(from: Int = 0): Int {
    val index = indexOfFirst(from) { !it.isWhitespace() }
    return index.takeIf { it >= 0 } ?: from
}

private inline fun String.indexOfFirst(startIndex: Int = 0, predicate: (Char) -> Boolean): Int {
    for (index in startIndex until length) {
        if (predicate(this[index])) {
            return index
        }
    }
    return -1
}

private fun String.findTopLevelChar(char: Char): Int? {
    var inSingleQuote = false
    var inDoubleQuote = false
    var roundDepth = 0
    var squareDepth = 0
    forEachIndexed { index, character ->
        when {
            character == '\'' && !inDoubleQuote -> inSingleQuote = !inSingleQuote
            character == '"' && !inSingleQuote -> inDoubleQuote = !inDoubleQuote
            !inSingleQuote && !inDoubleQuote && character == '(' -> roundDepth++
            !inSingleQuote && !inDoubleQuote && character == ')' -> roundDepth = (roundDepth - 1).coerceAtLeast(0)
            !inSingleQuote && !inDoubleQuote && character == '[' -> squareDepth++
            !inSingleQuote && !inDoubleQuote && character == ']' -> squareDepth = (squareDepth - 1).coerceAtLeast(0)
            !inSingleQuote && !inDoubleQuote && roundDepth == 0 && squareDepth == 0 && character == char -> return index
        }
    }
    return null
}

private fun String.indexOfFirstTopLevelDelimiter(vararg delimiters: Char): Int {
    var inSingleQuote = false
    var inDoubleQuote = false
    var roundDepth = 0
    var squareDepth = 0
    forEachIndexed { index, character ->
        when {
            character == '\'' && !inDoubleQuote -> inSingleQuote = !inSingleQuote
            character == '"' && !inSingleQuote -> inDoubleQuote = !inDoubleQuote
            !inSingleQuote && !inDoubleQuote && roundDepth == 0 && squareDepth == 0 && character in delimiters -> return index
            !inSingleQuote && !inDoubleQuote && character == '(' -> roundDepth++
            !inSingleQuote && !inDoubleQuote && character == ')' -> roundDepth = (roundDepth - 1).coerceAtLeast(0)
            !inSingleQuote && !inDoubleQuote && character == '[' -> squareDepth++
            !inSingleQuote && !inDoubleQuote && character == ']' -> squareDepth = (squareDepth - 1).coerceAtLeast(0)
        }
    }
    return -1
}

private data class ImpexHeaderBlockBuilder(
    val header: ImpexHeader,
    val valueLines: MutableList<ImpexValueLine> = mutableListOf(),
) {
    fun build(): ImpexHeaderBlock = ImpexHeaderBlock(header = header, valueLines = valueLines.toList())
}
