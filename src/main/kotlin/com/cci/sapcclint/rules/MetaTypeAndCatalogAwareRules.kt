package com.cci.sapcclint.rules

import com.cci.sapcclint.catalog.AttributeRecord
import com.cci.sapcclint.catalog.AnalysisCapability
import com.cci.sapcclint.catalog.RelationEndRecord
import com.cci.sapcclint.catalog.findAttributesByQualifier
import com.cci.sapcclint.catalog.findItemTypes
import com.cci.sapcclint.catalog.findRelationEndsByQualifier
import com.cci.sapcclint.catalog.hasLocalDeclaredType
import com.cci.sapcclint.itemsxml.model.CustomPropertyDecl
import com.cci.sapcclint.itemsxml.model.LocatedValue
import com.cci.sapcclint.itemsxml.model.SourcePosition
import java.nio.file.Path
import java.util.ArrayDeque

private val knownRootTypeNames = setOf(
    "Item",
    "GenericItem",
    "LocalizableItem",
    "ExtensibleItem",
    "Link",
    "EnumerationValue",
    "ComposedType",
    "AttributeDescriptor",
    "RelationDescriptor",
    "CatalogVersion",
)

private val primitiveTypeNames = setOf("byte", "short", "int", "long", "float", "double", "char", "boolean")
private val wellKnownAtomicTypeNames = setOf(
    "String",
    "java.lang.String",
    "java.io.Serializable",
    "java.math.BigDecimal",
    "java.util.Date",
)

private fun finding(
    ruleId: String,
    severity: FindingSeverity,
    message: String,
    file: Path,
    position: SourcePosition,
    entityKey: String? = null,
) = Finding(ruleId, severity, message, FindingLocation(file, position), entityKey)

private fun LocatedValue<*>.positionOr(fallback: SourcePosition): SourcePosition = location ?: fallback

private enum class InheritanceCheck {
    VALID,
    INVALID,
    UNKNOWN,
}

private enum class InheritanceTraversalStep {
    SKIP,
    ENQUEUE,
    VALID,
    UNKNOWN,
}

private fun RuleContext.classifyInheritance(typeName: String?, expectedRoot: String): InheritanceCheck {
    val normalizedTypeName = typeName?.trim()?.takeIf { it.isNotEmpty() } ?: return InheritanceCheck.UNKNOWN
    if (sameRootType(normalizedTypeName, expectedRoot)) {
        return InheritanceCheck.VALID
    }
    if (isImmediatelyInvalidInheritanceTarget(normalizedTypeName)) {
        return InheritanceCheck.INVALID
    }
    if (isKnownRootType(normalizedTypeName)) {
        return InheritanceCheck.INVALID
    }
    if (catalog.hasLocalDeclaredType(normalizedTypeName) && catalog.findItemTypes(normalizedTypeName).isEmpty()) {
        return InheritanceCheck.INVALID
    }

    return walkInheritanceTree(normalizedTypeName, expectedRoot)
}

private fun RuleContext.walkInheritanceTree(normalizedTypeName: String, expectedRoot: String): InheritanceCheck {
    val queue = ArrayDeque<String>()
    val visited = mutableSetOf<String>()
    var sawUnknownParent = false
    queue.add(normalizedTypeName)

    while (queue.isNotEmpty()) {
        when (processInheritanceNode(queue.removeFirst(), expectedRoot, visited, queue)) {
            InheritanceTraversalStep.VALID -> return InheritanceCheck.VALID
            InheritanceTraversalStep.UNKNOWN -> sawUnknownParent = true
            InheritanceTraversalStep.SKIP, InheritanceTraversalStep.ENQUEUE -> Unit
        }
    }

    return if (sawUnknownParent) InheritanceCheck.UNKNOWN else InheritanceCheck.INVALID
}

private fun RuleContext.processInheritanceNode(
    currentTypeName: String,
    expectedRoot: String,
    visited: MutableSet<String>,
    queue: ArrayDeque<String>,
): InheritanceTraversalStep {
    if (!visited.add(currentTypeName.lowercase())) {
        return InheritanceTraversalStep.SKIP
    }

    val records = catalog.findItemTypes(currentTypeName)
    if (records.isEmpty()) {
        return InheritanceTraversalStep.UNKNOWN
    }

    var sawUnknownParent = false
    records.forEach { record ->
        val parentTypeName = record.declaration.extendsType.value?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
        when (classifyParentType(parentTypeName, expectedRoot)) {
            InheritanceTraversalStep.VALID -> return InheritanceTraversalStep.VALID
            InheritanceTraversalStep.UNKNOWN -> sawUnknownParent = true
            InheritanceTraversalStep.ENQUEUE -> queue.add(parentTypeName)
            InheritanceTraversalStep.SKIP -> Unit
        }
    }
    return if (sawUnknownParent) InheritanceTraversalStep.UNKNOWN else InheritanceTraversalStep.SKIP
}

private fun RuleContext.classifyParentType(
    parentTypeName: String,
    expectedRoot: String,
): InheritanceTraversalStep {
    if (sameRootType(parentTypeName, expectedRoot)) {
        return InheritanceTraversalStep.VALID
    }
    if (isKnownRootType(parentTypeName)) {
        return InheritanceTraversalStep.SKIP
    }
    return if (catalog.findItemTypes(parentTypeName).isEmpty()) {
        InheritanceTraversalStep.UNKNOWN
    } else {
        InheritanceTraversalStep.ENQUEUE
    }
}

private fun isImmediatelyInvalidInheritanceTarget(typeName: String): Boolean {
    return typeName in primitiveTypeNames ||
        typeName in wellKnownAtomicTypeNames ||
        typeName.startsWith("java.") ||
        typeName.startsWith("javax.") ||
        typeName.startsWith("HYBRIS.")
}

private fun isKnownRootType(typeName: String): Boolean = knownRootTypeNames.any { sameRootType(it, typeName) }

private fun sameRootType(left: String, right: String): Boolean = left.compareTo(right, ignoreCase = true) == 0

private fun RuleContext.isCatalogVersionLike(typeName: String?): Boolean? {
    return when (classifyInheritance(typeName, expectedRoot = "CatalogVersion")) {
        InheritanceCheck.VALID -> true
        InheritanceCheck.INVALID -> false
        InheritanceCheck.UNKNOWN -> null
    }
}

private fun parsePropertyStringValue(property: CustomPropertyDecl): String? {
    return property.value.value
        ?.trim()
        ?.removeSurrounding("\"")
        ?.takeIf { it.isNotEmpty() }
}

private fun parseQualifierList(property: CustomPropertyDecl): List<String> {
    return parsePropertyStringValue(property)
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
}

private fun AttributeRecord.isUnique(): Boolean = declaration.modifiers?.unique?.value == true

private fun RelationEndRecord.isUnique(): Boolean = declaration.modifiers?.unique?.value == true

class TSItemMetaTypeNameMustPointToValidMetaTypeRule : TypeSystemRule {
    override val ruleId = "ItemMetaTypeNameMustPointToValidMetaType"
    override val defaultSeverity = FindingSeverity.ERROR

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.itemTypes.mapNotNull { itemType ->
                val metaTypeName = itemType.metaType.value ?: return@mapNotNull null
                when (context.classifyInheritance(metaTypeName, expectedRoot = "ComposedType")) {
                    InheritanceCheck.VALID, InheritanceCheck.UNKNOWN -> null
                    InheritanceCheck.INVALID -> finding(
                        ruleId = ruleId,
                        severity = defaultSeverity,
                        message = "Item metatype '$metaTypeName' must resolve to ComposedType or one of its subtypes.",
                        file = file.path,
                        position = itemType.metaType.positionOr(itemType.location),
                        entityKey = itemType.code.value,
                    )
                }
            }
        }
    }
}

class TSItemAttributeMetaTypeNameMustPointToValidMetaTypeRule : TypeSystemRule {
    override val ruleId = "ItemAttributeMetaTypeNameMustPointToValidMetaType"
    override val defaultSeverity = FindingSeverity.ERROR

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.itemTypes.flatMap { itemType ->
                itemType.attributes.mapNotNull { attribute ->
                    val metaTypeName = attribute.metaType.value ?: return@mapNotNull null
                    when (context.classifyInheritance(metaTypeName, expectedRoot = "AttributeDescriptor")) {
                        InheritanceCheck.VALID, InheritanceCheck.UNKNOWN -> null
                        InheritanceCheck.INVALID -> finding(
                            ruleId = ruleId,
                            severity = defaultSeverity,
                            message = "Attribute metatype '$metaTypeName' must resolve to AttributeDescriptor or one of its subtypes.",
                            file = file.path,
                            position = attribute.metaType.positionOr(attribute.location),
                            entityKey = itemType.code.value,
                        )
                    }
                }
            }
        }
    }
}

class TSRelationElementMetaTypeNameMustPointToValidMetaTypeRule : TypeSystemRule {
    override val ruleId = "RelationElementMetaTypeNameMustPointToValidMetaType"
    override val defaultSeverity = FindingSeverity.ERROR

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.relations.flatMap { relation ->
                listOfNotNull(relation.source, relation.target).mapNotNull { relationEnd ->
                    val metaTypeName = relationEnd.metaType.value ?: return@mapNotNull null
                    when (context.classifyInheritance(metaTypeName, expectedRoot = "RelationDescriptor")) {
                        InheritanceCheck.VALID, InheritanceCheck.UNKNOWN -> null
                        InheritanceCheck.INVALID -> finding(
                            ruleId = ruleId,
                            severity = defaultSeverity,
                            message = "Relation metatype '$metaTypeName' must resolve to RelationDescriptor or one of its subtypes.",
                            file = file.path,
                            position = relationEnd.metaType.positionOr(relationEnd.location),
                            entityKey = relation.code.value,
                        )
                    }
                }
            }
        }
    }
}

class TSCatalogAwareCatalogVersionAttributeQualifierRule : TypeSystemRule {
    override val ruleId = "CatalogAwareCatalogVersionAttributeQualifier"
    override val defaultSeverity = FindingSeverity.ERROR

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.itemTypes.mapNotNull { itemType ->
                val property = itemType.customProperties.firstOrNull {
                    it.name.value.equals("catalogVersionAttributeQualifier", ignoreCase = true)
                } ?: return@mapNotNull null
                val qualifier = parsePropertyStringValue(property) ?: return@mapNotNull null
                val includeAncestors = context.hasCapability(AnalysisCapability.FULL_REPO_ANCESTRY)

                val attributeMatches = context.catalog.findAttributesByQualifier(
                    typeName = itemType.code.value,
                    qualifier = qualifier,
                    includeAncestors = includeAncestors,
                )
                val relationMatches = context.catalog.findRelationEndsByQualifier(
                    typeName = itemType.code.value,
                    qualifier = qualifier,
                    includeAncestors = includeAncestors,
                )
                if (!includeAncestors && attributeMatches.isEmpty() && relationMatches.isEmpty()) {
                    return@mapNotNull null
                }

                val hasCatalogVersionMatch = attributeMatches.any {
                    context.isCatalogVersionLike(it.declaration.type.value) == true
                } || relationMatches.any {
                    context.isCatalogVersionLike(it.declaration.type.value) == true
                }
                val hasUnknownCatalogVersionMatch = attributeMatches.any {
                    context.isCatalogVersionLike(it.declaration.type.value) == null
                } || relationMatches.any {
                    context.isCatalogVersionLike(it.declaration.type.value) == null
                }

                if (hasCatalogVersionMatch || hasUnknownCatalogVersionMatch) {
                    return@mapNotNull null
                }

                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "catalogVersionAttributeQualifier must point to an attribute or relation qualifier of type CatalogVersion.",
                    file = file.path,
                    position = property.value.positionOr(property.location),
                    entityKey = itemType.code.value,
                )
            }
        }
    }
}

class TSCatalogAwareUniqueKeyAttributeQualifierRule : TypeSystemRule {
    override val ruleId = "CatalogAwareUniqueKeyAttributeQualifier"
    override val defaultSeverity = FindingSeverity.ERROR

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.itemTypes.mapNotNull { itemType ->
                val property = itemType.customProperties.firstOrNull {
                    it.name.value.equals("uniqueKeyAttributeQualifier", ignoreCase = true)
                } ?: return@mapNotNull null
                val qualifiers = parseQualifierList(property)
                if (qualifiers.isEmpty()) {
                    return@mapNotNull null
                }
                val includeAncestors = context.hasCapability(AnalysisCapability.FULL_REPO_ANCESTRY)

                val nonUniqueQualifiers = qualifiers.filter { qualifier ->
                    val attributeMatches = context.catalog.findAttributesByQualifier(
                        typeName = itemType.code.value,
                        qualifier = qualifier,
                        includeAncestors = includeAncestors,
                    )
                    val relationMatches = context.catalog.findRelationEndsByQualifier(
                        typeName = itemType.code.value,
                        qualifier = qualifier,
                        includeAncestors = includeAncestors,
                    )
                    if (!includeAncestors && attributeMatches.isEmpty() && relationMatches.isEmpty()) {
                        return@filter false
                    }
                    (attributeMatches.none { it.isUnique() } && relationMatches.none { it.isUnique() })
                }

                if (nonUniqueQualifiers.isEmpty()) {
                    return@mapNotNull null
                }

                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "uniqueKeyAttributeQualifier must reference unique attributes or relation qualifiers only: ${nonUniqueQualifiers.joinToString(", ")}.",
                    file = file.path,
                    position = property.value.positionOr(property.location),
                    entityKey = itemType.code.value,
                )
            }
        }
    }
}
