package com.cci.sapcclint.catalog

import com.cci.sapcclint.itemsxml.model.ParsedItemsFile
import java.nio.file.Files
import java.nio.file.Path

private val knownPlatformRootTypes = setOf(
    "Item",
    "GenericItem",
    "LocalizableItem",
    "ExtensibleItem",
    "Link",
    "EnumerationValue",
)

private val knownPlatformMetaTypes = setOf(
    "ComposedType",
    "AttributeDescriptor",
    "RelationDescriptor",
)

class TypeSystemCatalogBuilder {

    fun build(files: List<ParsedItemsFile>): TypeSystemCatalog {
        val itemTypes = files.flatMap { file -> file.itemTypes.map { ItemTypeRecord(file.path, it) } }
        val relations = files.flatMap { file -> file.relations.map { RelationRecord(file.path, it) } }
        val enumTypes = files.flatMap { file -> file.enumTypes.map { EnumTypeRecord(file.path, it) } }
        val collectionTypes = files.flatMap { file -> file.collectionTypes.map { CollectionTypeRecord(file.path, it) } }
        val mapTypes = files.flatMap { file -> file.mapTypes.map { MapTypeRecord(file.path, it) } }
        val repoRoot = findRepositoryRoot(files)
        val declaredTypeNames = buildDeclaredTypeNames(itemTypes, enumTypes, collectionTypes, mapTypes)

        return TypeSystemCatalog(
            repoRoot = repoRoot,
            capabilities = buildCapabilities(itemTypes, declaredTypeNames, repoRoot),
            files = files,
            itemTypesByCode = itemTypes.groupByValue { it.declaration.code.value },
            relationsByCode = relations.groupByValue { it.declaration.code.value },
            enumTypesByCode = enumTypes.groupByValue { it.declaration.code.value },
            collectionTypesByCode = collectionTypes.groupByValue { it.declaration.code.value },
            mapTypesByCode = mapTypes.groupByValue { it.declaration.code.value },
            declaredTypeNames = declaredTypeNames,
            deploymentsByTable = buildDeploymentTableIndex(itemTypes, relations),
            deploymentsByTypeCode = buildDeploymentTypeCodeIndex(itemTypes, relations),
            relationEndsByType = buildRelationEndIndex(relations),
            attributesByType = buildAttributeIndex(itemTypes),
        )
    }

    private fun buildDeclaredTypeNames(
        itemTypes: List<ItemTypeRecord>,
        enumTypes: List<EnumTypeRecord>,
        collectionTypes: List<CollectionTypeRecord>,
        mapTypes: List<MapTypeRecord>,
    ): Set<String> {
        return buildSet {
            itemTypes.mapNotNullTo(this) { it.declaration.code.value }
            enumTypes.mapNotNullTo(this) { it.declaration.code.value }
            collectionTypes.mapNotNullTo(this) { it.declaration.code.value }
            mapTypes.mapNotNullTo(this) { it.declaration.code.value }
        }
    }

    private fun buildCapabilities(
        itemTypes: List<ItemTypeRecord>,
        declaredTypeNames: Set<String>,
        repoRoot: Path?,
    ): Set<AnalysisCapability> {
        return buildSet {
            if (itemTypes.isNotEmpty() || declaredTypeNames.isNotEmpty()) {
                add(AnalysisCapability.LOCAL_TYPE_SYSTEM)
            }
            if (repoRoot != null && containsReservedTypeCodeFile(repoRoot)) {
                add(AnalysisCapability.PLATFORM_RESERVED_TYPECODES)
            }
            if (knownPlatformMetaTypes.all { it in declaredTypeNames }) {
                add(AnalysisCapability.PLATFORM_META_TYPES)
            }
            if (itemTypes.isNotEmpty() && itemTypes.all { hasKnownAncestry(it, declaredTypeNames) }) {
                add(AnalysisCapability.FULL_REPO_ANCESTRY)
            }
        }
    }

    private fun buildDeploymentTableIndex(
        itemTypes: List<ItemTypeRecord>,
        relations: List<RelationRecord>,
    ): Map<String, List<DeploymentRecord>> {
        return (itemTypes.mapNotNull { item ->
            item.declaration.deployment?.table?.value?.let {
                it to DeploymentRecord(
                    ownerKind = DeploymentOwnerKind.ITEM_TYPE,
                    ownerName = item.declaration.code.value,
                    file = item.file,
                    declaration = item.declaration,
                )
            }
        } + relations.mapNotNull { relation ->
            relation.declaration.deployment?.table?.value?.let {
                it to DeploymentRecord(
                    ownerKind = DeploymentOwnerKind.RELATION,
                    ownerName = relation.declaration.code.value,
                    file = relation.file,
                    declaration = relation.declaration,
                )
            }
        }).groupBy({ it.first }, { it.second })
    }

    private fun buildDeploymentTypeCodeIndex(
        itemTypes: List<ItemTypeRecord>,
        relations: List<RelationRecord>,
    ): Map<String, List<DeploymentRecord>> {
        return (itemTypes.mapNotNull { item ->
            item.declaration.deployment?.typeCode?.value?.let {
                it to DeploymentRecord(
                    ownerKind = DeploymentOwnerKind.ITEM_TYPE,
                    ownerName = item.declaration.code.value,
                    file = item.file,
                    declaration = item.declaration,
                )
            }
        } + relations.mapNotNull { relation ->
            relation.declaration.deployment?.typeCode?.value?.let {
                it to DeploymentRecord(
                    ownerKind = DeploymentOwnerKind.RELATION,
                    ownerName = relation.declaration.code.value,
                    file = relation.file,
                    declaration = relation.declaration,
                )
            }
        }).groupBy({ it.first }, { it.second })
    }

    private fun buildRelationEndIndex(relations: List<RelationRecord>): Map<String, List<RelationEndRecord>> {
        return relations.flatMap { relation ->
            buildList {
                val source = relation.declaration.source
                val target = relation.declaration.target

                if (source != null && target != null && source.type.value != null && target.navigable.value != false) {
                    add(source.type.value!! to RelationEndRecord(relation = relation, declaration = target))
                }
                if (target != null && source != null && target.type.value != null && source.navigable.value != false) {
                    add(target.type.value!! to RelationEndRecord(relation = relation, declaration = source))
                }
            }
        }.groupBy({ it.first }, { it.second })
    }

    private fun buildAttributeIndex(itemTypes: List<ItemTypeRecord>): Map<String, List<AttributeRecord>> {
        return itemTypes.flatMap { itemType ->
            itemType.declaration.attributes.map { attribute ->
                itemType.declaration.code.value to AttributeRecord(owner = itemType, declaration = attribute)
            }
        }.mapNotNull { (typeName, record) ->
            typeName?.let { it to record }
        }.groupBy({ it.first }, { it.second })
    }

    private fun findRepositoryRoot(files: List<ParsedItemsFile>): Path? {
        val directories = files.asSequence()
            .map { it.path.toAbsolutePath().normalize().parent ?: it.path.toAbsolutePath().normalize() }
            .distinct()
            .toList()
        if (directories.isEmpty()) {
            return null
        }

        val markerRoot = directories
            .flatMap { directory -> generateSequence(directory) { it.parent }.toList() }
            .distinct()
            .firstOrNull { directory ->
                Files.exists(directory.resolve(".git")) ||
                    Files.exists(directory.resolve("gradlew")) ||
                    Files.exists(directory.resolve(".sapcc-lint.yml")) ||
                    Files.isDirectory(directory.resolve("custom")) ||
                    Files.isDirectory(directory.resolve("platform")) ||
                    Files.isDirectory(directory.resolve("bin/custom")) ||
                    Files.isDirectory(directory.resolve("bin/platform"))
            }
        if (markerRoot != null) {
            return markerRoot
        }

        return directories.reduce { current, next ->
            var candidate = current
            while (!next.startsWith(candidate)) {
                candidate = candidate.parent ?: return@reduce next.root ?: next
            }
            candidate
        }.takeIf { Files.exists(it) }
    }

    private fun containsReservedTypeCodeFile(repoRoot: Path): Boolean {
        Files.walk(repoRoot).use { stream ->
            return stream.anyMatch { Files.isRegularFile(it) && it.fileName.toString() == "reservedTypecodes.txt" }
        }
    }

    private fun hasKnownAncestry(
        itemType: ItemTypeRecord,
        declaredTypeNames: Set<String>,
    ): Boolean {
        val extendsType = itemType.declaration.extendsType.value ?: return true
        return extendsType in declaredTypeNames || extendsType in knownPlatformRootTypes
    }
}

private inline fun <T> List<T>.groupByValue(selector: (T) -> String?): Map<String, List<T>> {
    return mapNotNull { entry ->
        selector(entry)?.let { it to entry }
    }.groupBy({ it.first }, { it.second })
}
