package ai.nina.labs.sapcclint.itemsxml.parser

import ai.nina.labs.sapcclint.itemsxml.model.AttributeDecl
import ai.nina.labs.sapcclint.itemsxml.model.CollectionTypeDecl
import ai.nina.labs.sapcclint.itemsxml.model.CustomPropertyDecl
import ai.nina.labs.sapcclint.itemsxml.model.DeploymentDecl
import ai.nina.labs.sapcclint.itemsxml.model.EnumTypeDecl
import ai.nina.labs.sapcclint.itemsxml.model.EnumValueDecl
import ai.nina.labs.sapcclint.itemsxml.model.ItemTypeDecl
import ai.nina.labs.sapcclint.itemsxml.model.IndexDecl
import ai.nina.labs.sapcclint.itemsxml.model.IndexKeyDecl
import ai.nina.labs.sapcclint.itemsxml.model.LocatedValue
import ai.nina.labs.sapcclint.itemsxml.model.MapTypeDecl
import ai.nina.labs.sapcclint.itemsxml.model.ModifiersDecl
import ai.nina.labs.sapcclint.itemsxml.model.ParsedItemsFile
import ai.nina.labs.sapcclint.itemsxml.model.PersistenceDecl
import ai.nina.labs.sapcclint.itemsxml.model.RelationDecl
import ai.nina.labs.sapcclint.itemsxml.model.RelationEnd
import ai.nina.labs.sapcclint.itemsxml.model.RelationEndDecl
import ai.nina.labs.sapcclint.itemsxml.model.SourcePosition
import java.io.Reader
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

private const val CUSTOM_PROPERTIES_ELEMENT = "custom-properties"

/**
 * Parses SAP Commerce items.xml files into a typed model with source positions.
 */
class ItemsXmlParser {

    private val inputFactory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
    }

    fun parse(path: Path): ParsedItemsFile {
        val source = Files.readString(path)
        return parse(source, path)
    }

    fun parse(reader: Reader, path: Path): ParsedItemsFile {
        return parse(reader.readText(), path)
    }

    private fun parse(source: String, path: Path): ParsedItemsFile {
        val sourceLocator = SourceLocator(source)
        val streamReader = inputFactory.createXMLStreamReader(StringReader(source))
        return streamReader.use {
            val itemTypes = mutableListOf<ItemTypeDecl>()
            val relations = mutableListOf<RelationDecl>()
            val enumTypes = mutableListOf<EnumTypeDecl>()
            val collectionTypes = mutableListOf<CollectionTypeDecl>()
            val mapTypes = mutableListOf<MapTypeDecl>()

            while (streamReader.hasNext()) {
                if (streamReader.next() == XMLStreamConstants.START_ELEMENT && streamReader.localName == "items") {
                    parseItems(streamReader, sourceLocator, itemTypes, relations, enumTypes, collectionTypes, mapTypes)
                }
            }

            ParsedItemsFile(
                path = path,
                itemTypes = itemTypes,
                relations = relations,
                enumTypes = enumTypes,
                collectionTypes = collectionTypes,
                mapTypes = mapTypes,
            )
        }
    }

    private fun parseItems(
        reader: XMLStreamReader,
        sourceLocator: SourceLocator,
        itemTypes: MutableList<ItemTypeDecl>,
        relations: MutableList<RelationDecl>,
        enumTypes: MutableList<EnumTypeDecl>,
        collectionTypes: MutableList<CollectionTypeDecl>,
        mapTypes: MutableList<MapTypeDecl>,
    ) {
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "itemtypes" -> itemTypes += parseItemTypes(reader, sourceLocator)
                    "relations" -> relations += parseRelations(reader, sourceLocator)
                    "enumtypes" -> enumTypes += parseEnumTypes(reader, sourceLocator)
                    "collectiontypes" -> collectionTypes += parseCollectionTypes(reader, sourceLocator)
                    "maptypes" -> mapTypes += parseMapTypes(reader, sourceLocator)
                    else -> skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "items") {
                    return
                }
            }
        }
    }

    private fun parseItemTypes(reader: XMLStreamReader, sourceLocator: SourceLocator): List<ItemTypeDecl> {
        val itemTypes = mutableListOf<ItemTypeDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "itemtype" -> itemTypes += parseItemType(reader, sourceLocator)
                    "typegroup" -> itemTypes += parseTypeGroup(reader, sourceLocator)
                    else -> skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "itemtypes") {
                    return itemTypes
                }
            }
        }

        return itemTypes
    }

    private fun parseTypeGroup(reader: XMLStreamReader, sourceLocator: SourceLocator): List<ItemTypeDecl> {
        val itemTypes = mutableListOf<ItemTypeDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> if (reader.localName == "itemtype") {
                    itemTypes += parseItemType(reader, sourceLocator)
                } else {
                    skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "typegroup") {
                    return itemTypes
                }
            }
        }

        return itemTypes
    }

    private fun parseItemType(reader: XMLStreamReader, sourceLocator: SourceLocator): ItemTypeDecl {
        val location = reader.currentPosition()
        val code = reader.attributeText("code", sourceLocator)
        val extendsType = reader.attributeText("extends", sourceLocator)
        val jaloClass = reader.attributeText("jaloclass", sourceLocator)
        val autoCreate = reader.attributeBooleanValue("autocreate", sourceLocator)
        val generate = reader.attributeBooleanValue("generate", sourceLocator)
        val isAbstract = reader.attributeBooleanValue("abstract", sourceLocator)
        val metaType = reader.attributeText("metatype", sourceLocator)
        var deployment: DeploymentDecl? = null
        val indexes = mutableListOf<IndexDecl>()
        val attributes = mutableListOf<AttributeDecl>()
        val customProperties = mutableListOf<CustomPropertyDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "deployment" -> deployment = parseDeployment(reader, sourceLocator)
                    "indexes" -> indexes += parseIndexes(reader, sourceLocator)
                    "attributes" -> attributes += parseAttributes(reader, sourceLocator)
                    CUSTOM_PROPERTIES_ELEMENT -> customProperties += parseCustomProperties(reader, sourceLocator)
                    else -> skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "itemtype") {
                    return ItemTypeDecl(
                        code = code,
                        extendsType = extendsType,
                        jaloClass = jaloClass,
                        autoCreate = autoCreate,
                        generate = generate,
                        isAbstract = isAbstract,
                        metaType = metaType,
                        deployment = deployment,
                        indexes = indexes,
                        attributes = attributes,
                        customProperties = customProperties,
                        location = location,
                    )
                }
            }
        }

        return ItemTypeDecl(
            code = code,
            extendsType = extendsType,
            jaloClass = jaloClass,
            autoCreate = autoCreate,
            generate = generate,
            isAbstract = isAbstract,
            metaType = metaType,
            deployment = deployment,
            indexes = indexes,
            attributes = attributes,
            customProperties = customProperties,
            location = location,
        )
    }

    private fun parseIndexes(reader: XMLStreamReader, sourceLocator: SourceLocator): List<IndexDecl> {
        val indexes = mutableListOf<IndexDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> if (reader.localName == "index") {
                    indexes += parseIndex(reader, sourceLocator)
                } else {
                    skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "indexes") {
                    return indexes
                }
            }
        }

        return indexes
    }

    private fun parseIndex(reader: XMLStreamReader, sourceLocator: SourceLocator): IndexDecl {
        val location = reader.currentPosition()
        val name = reader.attributeText("name", sourceLocator)
        val keys = mutableListOf<IndexKeyDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "key" -> keys += parseIndexKey(reader, sourceLocator)
                    else -> skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "index") {
                    return IndexDecl(
                        name = name,
                        keys = keys,
                        location = location,
                    )
                }
            }
        }

        return IndexDecl(
            name = name,
            keys = keys,
            location = location,
        )
    }

    private fun parseIndexKey(reader: XMLStreamReader, sourceLocator: SourceLocator): IndexKeyDecl {
        val key = IndexKeyDecl(
            attribute = reader.attributeText("attribute", sourceLocator),
            location = reader.currentPosition(),
        )
        skipToEnd(reader, "key")
        return key
    }

    private fun parseAttributes(reader: XMLStreamReader, sourceLocator: SourceLocator): List<AttributeDecl> {
        val attributes = mutableListOf<AttributeDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> if (reader.localName == "attribute") {
                    attributes += parseAttribute(reader, sourceLocator)
                } else {
                    skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "attributes") {
                    return attributes
                }
            }
        }

        return attributes
    }

    private fun parseAttribute(reader: XMLStreamReader, sourceLocator: SourceLocator): AttributeDecl {
        val location = reader.currentPosition()
        val qualifier = reader.attributeText("qualifier", sourceLocator)
        val type = reader.attributeText("type", sourceLocator)
        val metaType = reader.attributeText("metatype", sourceLocator)
        val autoCreate = reader.attributeBooleanValue("autocreate", sourceLocator)
        val generate = reader.attributeBooleanValue("generate", sourceLocator)
        var defaultValue = LocatedValue<String>(value = null, location = null)
        var persistence: PersistenceDecl? = null
        var modifiers: ModifiersDecl? = null
        val customProperties = mutableListOf<CustomPropertyDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "defaultvalue" -> defaultValue = parseTextElement(reader, "defaultvalue")
                    "persistence" -> persistence = parsePersistence(reader, sourceLocator)
                    "modifiers" -> modifiers = parseModifiers(reader, sourceLocator)
                    CUSTOM_PROPERTIES_ELEMENT -> customProperties += parseCustomProperties(reader, sourceLocator)
                    else -> skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "attribute") {
                    return AttributeDecl(
                        qualifier = qualifier,
                        type = type,
                        metaType = metaType,
                        autoCreate = autoCreate,
                        generate = generate,
                        defaultValue = defaultValue,
                        persistence = persistence,
                        modifiers = modifiers,
                        customProperties = customProperties,
                        location = location,
                    )
                }
            }
        }

        return AttributeDecl(
            qualifier = qualifier,
            type = type,
            metaType = metaType,
            autoCreate = autoCreate,
            generate = generate,
            defaultValue = defaultValue,
            persistence = persistence,
            modifiers = modifiers,
            customProperties = customProperties,
            location = location,
        )
    }

    private fun parseRelations(reader: XMLStreamReader, sourceLocator: SourceLocator): List<RelationDecl> {
        val relations = mutableListOf<RelationDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> if (reader.localName == "relation") {
                    relations += parseRelation(reader, sourceLocator)
                } else {
                    skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "relations") {
                    return relations
                }
            }
        }

        return relations
    }

    private fun parseRelation(reader: XMLStreamReader, sourceLocator: SourceLocator): RelationDecl {
        val location = reader.currentPosition()
        val code = reader.attributeText("code", sourceLocator)
        var deployment: DeploymentDecl? = null
        var source: RelationEndDecl? = null
        var target: RelationEndDecl? = null

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "deployment" -> deployment = parseDeployment(reader, sourceLocator)
                    "sourceElement" -> source = parseRelationEnd(reader, RelationEnd.SOURCE, sourceLocator)
                    "targetElement" -> target = parseRelationEnd(reader, RelationEnd.TARGET, sourceLocator)
                    else -> skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "relation") {
                    return RelationDecl(
                        code = code,
                        deployment = deployment,
                        source = source,
                        target = target,
                        location = location,
                    )
                }
            }
        }

        return RelationDecl(code = code, deployment = deployment, source = source, target = target, location = location)
    }

    private fun parseRelationEnd(reader: XMLStreamReader, end: RelationEnd, sourceLocator: SourceLocator): RelationEndDecl {
        val location = reader.currentPosition()
        val type = reader.attributeText("type", sourceLocator)
        val qualifier = reader.attributeText("qualifier", sourceLocator)
        val metaType = reader.attributeText("metatype", sourceLocator)
        val cardinality = reader.attributeText("cardinality", sourceLocator)
        val navigable = reader.attributeBooleanValue("navigable", sourceLocator)
        val collectionType = reader.attributeText("collectiontype", sourceLocator)
        val ordered = reader.attributeBooleanValue("ordered", sourceLocator)
        var modifiers: ModifiersDecl? = null
        val customProperties = mutableListOf<CustomPropertyDecl>()
        val elementName = if (end == RelationEnd.SOURCE) "sourceElement" else "targetElement"

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                    "modifiers" -> modifiers = parseModifiers(reader, sourceLocator)
                    CUSTOM_PROPERTIES_ELEMENT -> customProperties += parseCustomProperties(reader, sourceLocator)
                    else -> skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == elementName) {
                    return RelationEndDecl(
                        end = end,
                        type = type,
                        qualifier = qualifier,
                        metaType = metaType,
                        cardinality = cardinality,
                        navigable = navigable,
                        collectionType = collectionType,
                        ordered = ordered,
                        modifiers = modifiers,
                        customProperties = customProperties,
                        location = location,
                    )
                }
            }
        }

        return RelationEndDecl(
            end = end,
            type = type,
            qualifier = qualifier,
            metaType = metaType,
            cardinality = cardinality,
            navigable = navigable,
            collectionType = collectionType,
            ordered = ordered,
            modifiers = modifiers,
            customProperties = customProperties,
            location = location,
        )
    }

    private fun parseEnumTypes(reader: XMLStreamReader, sourceLocator: SourceLocator): List<EnumTypeDecl> {
        val enumTypes = mutableListOf<EnumTypeDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> if (reader.localName == "enumtype") {
                    enumTypes += parseEnumType(reader, sourceLocator)
                } else {
                    skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "enumtypes") {
                    return enumTypes
                }
            }
        }

        return enumTypes
    }

    private fun parseEnumType(reader: XMLStreamReader, sourceLocator: SourceLocator): EnumTypeDecl {
        val location = reader.currentPosition()
        val code = reader.attributeText("code", sourceLocator)
        val jaloClass = reader.attributeText("jaloclass", sourceLocator)
        val autoCreate = reader.attributeBooleanValue("autocreate", sourceLocator)
        val generate = reader.attributeBooleanValue("generate", sourceLocator)
        val dynamic = reader.attributeBooleanValue("dynamic", sourceLocator)
        val values = mutableListOf<EnumValueDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> if (reader.localName == "value") {
                    values += parseEnumValue(reader, sourceLocator)
                } else {
                    skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "enumtype") {
                    return EnumTypeDecl(
                        code = code,
                        jaloClass = jaloClass,
                        autoCreate = autoCreate,
                        generate = generate,
                        dynamic = dynamic,
                        values = values,
                        location = location,
                    )
                }
            }
        }

        return EnumTypeDecl(
            code = code,
            jaloClass = jaloClass,
            autoCreate = autoCreate,
            generate = generate,
            dynamic = dynamic,
            values = values,
            location = location,
        )
    }

    private fun parseEnumValue(reader: XMLStreamReader, sourceLocator: SourceLocator): EnumValueDecl {
        val location = reader.currentPosition()
        val code = reader.attributeText("code", sourceLocator)
        skipToEnd(reader, "value")
        return EnumValueDecl(code = code, location = location)
    }

    private fun parseCollectionTypes(reader: XMLStreamReader, sourceLocator: SourceLocator): List<CollectionTypeDecl> {
        val collectionTypes = mutableListOf<CollectionTypeDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> if (reader.localName == "collectiontype") {
                    collectionTypes += parseCollectionType(reader, sourceLocator)
                } else {
                    skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "collectiontypes") {
                    return collectionTypes
                }
            }
        }

        return collectionTypes
    }

    private fun parseCollectionType(reader: XMLStreamReader, sourceLocator: SourceLocator): CollectionTypeDecl {
        val collectionType = CollectionTypeDecl(
            code = reader.attributeText("code", sourceLocator),
            elementType = reader.attributeText("elementtype", sourceLocator),
            type = reader.attributeText("type", sourceLocator),
            autoCreate = reader.attributeBooleanValue("autocreate", sourceLocator),
            generate = reader.attributeBooleanValue("generate", sourceLocator),
            location = reader.currentPosition(),
        )
        skipToEnd(reader, "collectiontype")
        return collectionType
    }

    private fun parseMapTypes(reader: XMLStreamReader, sourceLocator: SourceLocator): List<MapTypeDecl> {
        val mapTypes = mutableListOf<MapTypeDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> if (reader.localName == "maptype") {
                    mapTypes += parseMapType(reader, sourceLocator)
                } else {
                    skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "maptypes") {
                    return mapTypes
                }
            }
        }

        return mapTypes
    }

    private fun parseMapType(reader: XMLStreamReader, sourceLocator: SourceLocator): MapTypeDecl {
        val mapType = MapTypeDecl(
            code = reader.attributeText("code", sourceLocator),
            argumentType = reader.attributeText("argumenttype", sourceLocator),
            returnType = reader.attributeText("returntype", sourceLocator),
            autoCreate = reader.attributeBooleanValue("autocreate", sourceLocator),
            generate = reader.attributeBooleanValue("generate", sourceLocator),
            location = reader.currentPosition(),
        )
        skipToEnd(reader, "maptype")
        return mapType
    }

    private fun parseDeployment(reader: XMLStreamReader, sourceLocator: SourceLocator): DeploymentDecl {
        val deployment = DeploymentDecl(
            table = reader.attributeText("table", sourceLocator),
            typeCode = reader.attributeText("typecode", sourceLocator),
            propertyTable = reader.attributeText("propertytable", sourceLocator),
            location = reader.currentPosition(),
        )
        skipToEnd(reader, "deployment")
        return deployment
    }

    private fun parsePersistence(reader: XMLStreamReader, sourceLocator: SourceLocator): PersistenceDecl {
        val persistence = PersistenceDecl(
            type = reader.attributeText("type", sourceLocator),
            attributeHandler = reader.attributeText("attributeHandler", sourceLocator),
            location = reader.currentPosition(),
        )
        skipToEnd(reader, "persistence")
        return persistence
    }

    private fun parseModifiers(reader: XMLStreamReader, sourceLocator: SourceLocator): ModifiersDecl {
        val modifiers = ModifiersDecl(
            read = reader.attributeBooleanValue("read", sourceLocator),
            write = reader.attributeBooleanValue("write", sourceLocator),
            search = reader.attributeBooleanValue("search", sourceLocator),
            optional = reader.attributeBooleanValue("optional", sourceLocator),
            initial = reader.attributeBooleanValue("initial", sourceLocator),
            unique = reader.attributeBooleanValue("unique", sourceLocator),
            partOf = reader.attributeBooleanValue("partof", sourceLocator),
            doNotOptimize = reader.attributeBooleanValue("dontOptimize", sourceLocator),
            location = reader.currentPosition(),
        )
        skipToEnd(reader, "modifiers")
        return modifiers
    }

    private fun parseCustomProperties(reader: XMLStreamReader, sourceLocator: SourceLocator): List<CustomPropertyDecl> {
        val customProperties = mutableListOf<CustomPropertyDecl>()

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> if (reader.localName == "property") {
                    customProperties += parseCustomProperty(reader, sourceLocator)
                } else {
                    skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == CUSTOM_PROPERTIES_ELEMENT) {
                    return customProperties
                }
            }
        }

        return customProperties
    }

    private fun parseCustomProperty(reader: XMLStreamReader, sourceLocator: SourceLocator): CustomPropertyDecl {
        val location = reader.currentPosition()
        val name = reader.attributeText("name", sourceLocator)
        var value = LocatedValue<String>(value = null, location = null)

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> if (reader.localName == "value") {
                    value = parseTextElement(reader, "value")
                } else {
                    skipElement(reader)
                }

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == "property") {
                    return CustomPropertyDecl(name = name, value = value, location = location)
                }
            }
        }

        return CustomPropertyDecl(name = name, value = value, location = location)
    }

    private fun parseTextElement(reader: XMLStreamReader, elementName: String): LocatedValue<String> {
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

                XMLStreamConstants.END_ELEMENT -> if (reader.localName == elementName) {
                    depth--
                }
            }
        }

        return LocatedValue(
            value = text.toString().trim().takeIf { it.isNotEmpty() },
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

    private fun XMLStreamReader.attributeValue(name: String): String? {
        val index = (0 until attributeCount).firstOrNull { getAttributeLocalName(it) == name } ?: return null
        return getAttributeValue(index)
    }

    private fun XMLStreamReader.attributeText(name: String, sourceLocator: SourceLocator): LocatedValue<String> {
        val value = attributeValue(name)
        val location = sourceLocator.findAttributePosition(currentPosition(), name)
        return LocatedValue(value = value, location = location)
    }

    private fun XMLStreamReader.attributeBooleanValue(name: String, sourceLocator: SourceLocator): LocatedValue<Boolean> {
        val value = attributeValue(name)?.lowercase()?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
        val location = sourceLocator.findAttributePosition(currentPosition(), name)
        return LocatedValue(value = value, location = location)
    }

    private fun XMLStreamReader.use(block: () -> ParsedItemsFile): ParsedItemsFile {
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
            val tagEndOffset = source.indexOf('>', startIndex = startOffset)
                .takeIf { it >= 0 }
                ?: return null
            val tagSource = source.substring(startOffset, tagEndOffset)
            val matchIndex = Regex("""\b${Regex.escape(attributeName)}\s*=""").find(tagSource)?.range?.first
                ?: return null
            return positionFromOffset(startOffset + matchIndex)
        }

        private fun offsetFromPosition(position: SourcePosition): Int {
            val lineOffset = lineStartOffsets.getOrNull(position.line - 1) ?: return source.length
            return (lineOffset + position.column - 1).coerceAtMost(source.length)
        }

        private fun tagStartOffset(position: SourcePosition): Int {
            val approximateOffset = offsetFromPosition(position)
            return source.lastIndexOf('<', startIndex = approximateOffset)
                .takeIf { it >= 0 }
                ?: approximateOffset
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
