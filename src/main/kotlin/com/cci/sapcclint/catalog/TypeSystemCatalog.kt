package com.cci.sapcclint.catalog

import com.cci.sapcclint.itemsxml.model.AttributeDecl
import com.cci.sapcclint.itemsxml.model.CollectionTypeDecl
import com.cci.sapcclint.itemsxml.model.CustomPropertyDecl
import com.cci.sapcclint.itemsxml.model.EnumTypeDecl
import com.cci.sapcclint.itemsxml.model.ItemTypeDecl
import com.cci.sapcclint.itemsxml.model.MapTypeDecl
import com.cci.sapcclint.itemsxml.model.ParsedItemsFile
import com.cci.sapcclint.itemsxml.model.RelationDecl
import com.cci.sapcclint.itemsxml.model.RelationEndDecl
import java.nio.file.Path
import java.util.ArrayDeque

data class TypeSystemCatalog(
    val repoRoot: Path?,
    val capabilities: Set<AnalysisCapability>,
    val files: List<ParsedItemsFile>,
    val itemTypesByCode: Map<String, List<ItemTypeRecord>>,
    val relationsByCode: Map<String, List<RelationRecord>>,
    val enumTypesByCode: Map<String, List<EnumTypeRecord>>,
    val collectionTypesByCode: Map<String, List<CollectionTypeRecord>>,
    val mapTypesByCode: Map<String, List<MapTypeRecord>>,
    val declaredTypeNames: Set<String>,
    val deploymentsByTable: Map<String, List<DeploymentRecord>>,
    val deploymentsByTypeCode: Map<String, List<DeploymentRecord>>,
    val relationEndsByType: Map<String, List<RelationEndRecord>>,
    val attributesByType: Map<String, List<AttributeRecord>>,
)

enum class AnalysisCapability {
    LOCAL_TYPE_SYSTEM,
    PLATFORM_RESERVED_TYPECODES,
    PLATFORM_META_TYPES,
    FULL_REPO_ANCESTRY,
}

data class ItemTypeRecord(
    val file: Path,
    val declaration: ItemTypeDecl,
)

data class AttributeRecord(
    val owner: ItemTypeRecord,
    val declaration: AttributeDecl,
)

data class RelationRecord(
    val file: Path,
    val declaration: RelationDecl,
)

data class EnumTypeRecord(
    val file: Path,
    val declaration: EnumTypeDecl,
)

data class CollectionTypeRecord(
    val file: Path,
    val declaration: CollectionTypeDecl,
)

data class MapTypeRecord(
    val file: Path,
    val declaration: MapTypeDecl,
)

data class RelationEndRecord(
    val relation: RelationRecord,
    val declaration: RelationEndDecl,
)

data class ItemCustomPropertyRecord(
    val owner: ItemTypeRecord,
    val declaration: CustomPropertyDecl,
)

data class DeploymentRecord(
    val ownerKind: DeploymentOwnerKind,
    val ownerName: String?,
    val file: Path,
    val declaration: Any,
)

enum class DeploymentOwnerKind {
    ITEM_TYPE,
    RELATION
}

fun TypeSystemCatalog.findItemTypes(code: String?): List<ItemTypeRecord> = itemTypesByCode[code].orEmpty()

fun TypeSystemCatalog.findRelations(code: String?): List<RelationRecord> = relationsByCode[code].orEmpty()

fun TypeSystemCatalog.findEnumTypes(code: String?): List<EnumTypeRecord> = enumTypesByCode[code].orEmpty()

fun TypeSystemCatalog.findCollectionTypes(code: String?): List<CollectionTypeRecord> = collectionTypesByCode[code].orEmpty()

fun TypeSystemCatalog.findMapTypes(code: String?): List<MapTypeRecord> = mapTypesByCode[code].orEmpty()

fun TypeSystemCatalog.findDeploymentTable(table: String?): List<DeploymentRecord> = deploymentsByTable[table].orEmpty()

fun TypeSystemCatalog.findDeploymentTypeCode(typeCode: String?): List<DeploymentRecord> = deploymentsByTypeCode[typeCode].orEmpty()

fun TypeSystemCatalog.findRelationEnds(typeName: String?): List<RelationEndRecord> = relationEndsByType[typeName].orEmpty()

fun TypeSystemCatalog.findRelationEndsByQualifier(
    typeName: String?,
    qualifier: String?,
    includeAncestors: Boolean = false,
): List<RelationEndRecord> {
    return findTypeHierarchy(typeName, includeSelf = true, includeAncestors = includeAncestors)
        .flatMap { relationEndsByType[it.declaration.code.value].orEmpty() }
        .filter { it.declaration.qualifier.value == qualifier }
}

fun TypeSystemCatalog.findDeclaredAttributes(typeName: String?): List<AttributeRecord> = attributesByType[typeName].orEmpty()

fun TypeSystemCatalog.findAttributes(
    typeName: String?,
    includeAncestors: Boolean = false,
): List<AttributeRecord> {
    return findTypeHierarchy(typeName, includeSelf = true, includeAncestors = includeAncestors)
        .flatMap { attributesByType[it.declaration.code.value].orEmpty() }
}

fun TypeSystemCatalog.findAttributesByQualifier(
    typeName: String?,
    qualifier: String?,
    includeAncestors: Boolean = false,
): List<AttributeRecord> {
    return findAttributes(typeName, includeAncestors)
        .filter { it.declaration.qualifier.value == qualifier }
}

fun TypeSystemCatalog.findItemCustomProperties(
    typeName: String?,
    includeAncestors: Boolean = false,
): List<ItemCustomPropertyRecord> {
    return findTypeHierarchy(typeName, includeSelf = true, includeAncestors = includeAncestors)
        .flatMap { owner ->
            owner.declaration.customProperties.map { property ->
                ItemCustomPropertyRecord(owner = owner, declaration = property)
            }
        }
}

fun TypeSystemCatalog.findItemCustomPropertiesByName(
    typeName: String?,
    propertyName: String?,
    includeAncestors: Boolean = false,
): List<ItemCustomPropertyRecord> {
    return findItemCustomProperties(typeName, includeAncestors)
        .filter { it.declaration.name.value == propertyName }
}

fun TypeSystemCatalog.findIndexedAttributeQualifiers(
    typeName: String?,
    includeAncestors: Boolean = false,
): Set<String> {
    return findTypeHierarchy(typeName, includeSelf = true, includeAncestors = includeAncestors)
        .flatMap { itemType ->
            itemType.declaration.indexes.flatMap { index ->
                index.keys.mapNotNull { it.attribute.value }
            }
        }
        .toSet()
}

fun TypeSystemCatalog.isSameOrSubtypeOf(
    typeName: String?,
    expectedTypeName: String?,
): Boolean {
    if (typeName == null || expectedTypeName == null) {
        return false
    }
    if (sameTypeName(typeName, expectedTypeName)) {
        return true
    }

    return findTypeHierarchy(typeName, includeSelf = false, includeAncestors = true)
        .any { sameTypeName(it.declaration.code.value, expectedTypeName) }
}

private fun sameTypeName(left: String?, right: String?): Boolean = left != null && right != null && left.compareTo(right, ignoreCase = true) == 0

fun TypeSystemCatalog.findNearestDeployedAncestor(itemType: ItemTypeRecord): ItemTypeRecord? {
    return findTypeHierarchy(itemType.declaration.code.value, includeSelf = false, includeAncestors = true)
        .firstOrNull { it.declaration.deployment != null }
}

fun TypeSystemCatalog.hasLocalDeclaredType(typeName: String?): Boolean = typeName != null && typeName in declaredTypeNames

fun TypeSystemCatalog.hasCapability(capability: AnalysisCapability): Boolean = capability in capabilities

fun TypeSystemCatalog.findTypeHierarchy(
    typeName: String?,
    includeSelf: Boolean,
    includeAncestors: Boolean,
): List<ItemTypeRecord> {
    if (typeName == null) {
        return emptyList()
    }

    val collected = mutableListOf<ItemTypeRecord>()
    val visitedTypeNames = linkedSetOf<String>()
    val queue = ArrayDeque<String>()

    if (includeSelf) {
        queue.add(typeName)
    } else if (includeAncestors) {
        findItemTypes(typeName).mapNotNullTo(queue) { it.declaration.extendsType.value }
    }

    while (queue.isNotEmpty()) {
        val currentType = queue.removeFirst()
        if (!visitedTypeNames.add(currentType)) {
            continue
        }

        val records = findItemTypes(currentType)
        if (records.isEmpty()) {
            continue
        }

        collected += records

        if (includeAncestors) {
            records.mapNotNullTo(queue) { it.declaration.extendsType.value }
        }
    }

    return collected
}
