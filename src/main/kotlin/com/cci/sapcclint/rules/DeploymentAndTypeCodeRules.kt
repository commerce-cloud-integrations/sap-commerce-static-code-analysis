package com.cci.sapcclint.rules

import com.cci.sapcclint.catalog.findDeploymentTable
import com.cci.sapcclint.catalog.findDeploymentTypeCode
import com.cci.sapcclint.catalog.findNearestDeployedAncestor
import com.cci.sapcclint.itemsxml.model.LocatedValue
import com.cci.sapcclint.itemsxml.model.RelationDecl
import com.cci.sapcclint.itemsxml.model.RelationEndDecl
import com.cci.sapcclint.itemsxml.model.SourcePosition
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.inputStream

private const val DEFAULT_DEPLOYMENT_TABLE_NAME_MAX_LENGTH = 24
private const val TYPECODE_MIN_ALLOWED = 10000

private fun finding(
    ruleId: String,
    severity: FindingSeverity,
    message: String,
    file: Path,
    position: SourcePosition,
    entityKey: String? = null,
) = Finding(ruleId, severity, message, FindingLocation(file, position), entityKey)

private fun LocatedValue<*>.positionOr(fallback: SourcePosition): SourcePosition = location ?: fallback

private fun RelationEndDecl.cardinalityValue() = cardinality.value?.lowercase()

private fun RelationDecl.isManyToMany(): Boolean = source?.cardinalityValue() == "many" && target?.cardinalityValue() == "many"

private fun RelationDecl.isOneToManyLike(): Boolean = source?.cardinalityValue() == "one" || target?.cardinalityValue() == "one"

private fun RuleContext.repositoryRoot(): Path? {
    val directories = catalog.files.map { it.path.toAbsolutePath().normalize().parent ?: it.path.toAbsolutePath().normalize() }
    if (directories.isEmpty()) {
        return null
    }
    val markerRoot = candidateDirectories().firstOrNull { directory ->
        Files.exists(directory.resolve(".git")) ||
            Files.exists(directory.resolve("gradlew")) ||
            Files.exists(directory.resolve(".sapcc-lint.yml"))
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

private fun RuleContext.deploymentTableNameMaxLength(): Int {
    return candidateDirectories()
        .flatMap { directory -> sequenceOf(directory.resolve("local.properties"), directory.resolve("project.properties")) }
        .firstNotNullOfOrNull { file ->
            if (!Files.exists(file)) {
                null
            } else {
                Properties().also { file.inputStream().use(it::load) }
                    .getProperty("deployment.tablename.maxlength")
                    ?.toIntOrNull()
            }
        }
        ?: DEFAULT_DEPLOYMENT_TABLE_NAME_MAX_LENGTH
}

private fun RuleContext.reservedTypeCodes(): Map<Int, String> {
    val repoRoot = repositoryRoot() ?: return emptyMap()
    Files.walk(repoRoot).use { stream ->
        val reservedFile = stream
            .filter { Files.isRegularFile(it) && it.fileName.toString() == "reservedTypecodes.txt" }
            .findFirst()
            .orElse(null)
            ?: return emptyMap()

        return Properties().also { reservedFile.inputStream().use(it::load) }
            .entries
            .mapNotNull { entry ->
                entry.key.toString().toIntOrNull()?.let { it to entry.value.toString() }
            }
            .toMap()
    }
}

abstract class AbstractDeploymentRule(
    override val ruleId: String,
    override val defaultSeverity: FindingSeverity = FindingSeverity.ERROR,
) : TypeSystemRule

class TSDeploymentTableMustBeUniqueRule : AbstractDeploymentRule("TSDeploymentTableMustBeUnique") {
    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.itemTypes.mapNotNull { itemType ->
                val table = itemType.deployment?.table?.value ?: return@mapNotNull null
                if (context.catalog.findDeploymentTable(table).size <= 1) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Deployment table '$table' must be unique.",
                    file = file.path,
                    position = itemType.deployment.table.positionOr(itemType.deployment.location),
                    entityKey = itemType.code.value
                )
            } + file.relations.mapNotNull { relation ->
                val table = relation.deployment?.table?.value ?: return@mapNotNull null
                if (context.catalog.findDeploymentTable(table).size <= 1) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Deployment table '$table' must be unique.",
                    file = file.path,
                    position = relation.deployment.table.positionOr(relation.deployment.location),
                    entityKey = relation.code.value
                )
            }
        }
    }
}

private fun RuleContext.candidateDirectories(): Sequence<Path> {
    return catalog.files.asSequence()
        .flatMap { parsedFile ->
            generateSequence(parsedFile.path.toAbsolutePath().normalize().parent) { it.parent }
        }
        .distinct()
}

class TSDeploymentTypeCodeMustBeUniqueRule : AbstractDeploymentRule("TSDeploymentTypeCodeMustBeUnique") {
    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.itemTypes.mapNotNull { itemType ->
                val typeCode = itemType.deployment?.typeCode?.value ?: return@mapNotNull null
                if (context.catalog.findDeploymentTypeCode(typeCode).size <= 1) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Deployment typecode '$typeCode' must be unique.",
                    file = file.path,
                    position = itemType.deployment.typeCode.positionOr(itemType.deployment.location),
                    entityKey = itemType.code.value
                )
            } + file.relations.mapNotNull { relation ->
                val typeCode = relation.deployment?.typeCode?.value ?: return@mapNotNull null
                if (context.catalog.findDeploymentTypeCode(typeCode).size <= 1) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Deployment typecode '$typeCode' must be unique.",
                    file = file.path,
                    position = relation.deployment.typeCode.positionOr(relation.deployment.location),
                    entityKey = relation.code.value
                )
            }
        }
    }
}

class TSDeploymentTableMustExistForManyToManyRelationRule : AbstractDeploymentRule("TSDeploymentTableMustExistForManyToManyRelation") {
    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.relations.mapNotNull { relation ->
                if (!relation.isManyToMany() || relation.deployment?.typeCode?.value != null) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Many-to-many relation '${relation.code.value ?: "?"}' must declare a deployment typecode.",
                    file = file.path,
                    position = relation.deployment?.location ?: relation.location,
                    entityKey = relation.code.value
                )
            }
        }
    }
}

class TSDeploymentTagMustNotBeDeclaredForO2MRelationRule : AbstractDeploymentRule("TSDeploymentTagMustNotBeDeclaredForO2MRelation") {
    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.relations.mapNotNull { relation ->
                if (!relation.isOneToManyLike() || relation.deployment == null) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "One-to-many relation '${relation.code.value ?: "?"}' must not declare a deployment tag.",
                    file = file.path,
                    position = relation.deployment.location,
                    entityKey = relation.code.value
                )
            }
        }
    }
}

class TSDeploymentTableMustNotBeRedeclaredInChildTypesRule : AbstractDeploymentRule("TSDeploymentTableMustNotBeRedeclaredInChildTypes") {
    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.itemTypesByCode.values.flatten().mapNotNull { itemType ->
            val deployment = itemType.declaration.deployment ?: return@mapNotNull null
            val parent = context.catalog.findNearestDeployedAncestor(itemType) ?: return@mapNotNull null
            finding(
                ruleId = ruleId,
                severity = defaultSeverity,
                message = "Item type '${itemType.declaration.code.value ?: "?"}' must not redeclare deployment inherited from '${parent.declaration.code.value ?: "?"}'.",
                file = itemType.file,
                position = deployment.location,
                entityKey = itemType.declaration.code.value
            )
        }
    }
}

class TSDeploymentTableNameLengthShouldBeValidRule : AbstractDeploymentRule("TSDeploymentTableNameLengthShouldBeValid") {
    override fun evaluate(context: RuleContext): List<Finding> {
        val maxLength = context.deploymentTableNameMaxLength()
        return context.catalog.files.flatMap { file ->
            file.itemTypes.mapNotNull { itemType ->
                val table = itemType.deployment?.table?.value ?: return@mapNotNull null
                if (table.length <= maxLength) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Deployment table '$table' exceeds the maximum length of $maxLength.",
                    file = file.path,
                    position = itemType.deployment.table.positionOr(itemType.deployment.location),
                    entityKey = itemType.code.value
                )
            } + file.relations.mapNotNull { relation ->
                val table = relation.deployment?.table?.value ?: return@mapNotNull null
                if (table.length <= maxLength) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Deployment table '$table' exceeds the maximum length of $maxLength.",
                    file = file.path,
                    position = relation.deployment.table.positionOr(relation.deployment.location),
                    entityKey = relation.code.value
                )
            }
        }
    }
}

class TSDeploymentTypeCodeMustBeGreaterThanTenThousandRule : AbstractDeploymentRule("TSDeploymentTypeCodeMustBeGreaterThanTenThousand") {
    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.itemTypes.mapNotNull { itemType ->
                val typeCode = itemType.deployment?.typeCode?.value?.toIntOrNull() ?: return@mapNotNull null
                if (typeCode > TYPECODE_MIN_ALLOWED) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Deployment typecode '$typeCode' must be greater than $TYPECODE_MIN_ALLOWED.",
                    file = file.path,
                    position = itemType.deployment.typeCode.positionOr(itemType.deployment.location),
                    entityKey = itemType.code.value
                )
            }
        }
    }
}

class TSDeploymentTypeCodesMustBeGreaterThanTenThousandForRelationsRule : AbstractDeploymentRule("TSDeploymentTypeCodesMustBeGreaterThanTenThousandForRelations") {
    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.relations.mapNotNull { relation ->
                val typeCode = relation.deployment?.typeCode?.value?.toIntOrNull() ?: return@mapNotNull null
                if (typeCode > TYPECODE_MIN_ALLOWED) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Relation deployment typecode '$typeCode' must be greater than $TYPECODE_MIN_ALLOWED.",
                    file = file.path,
                    position = relation.deployment.typeCode.positionOr(relation.deployment.location),
                    entityKey = relation.code.value
                )
            }
        }
    }
}

abstract class AbstractReservedRangeRule(
    ruleId: String,
    private val range: IntRange,
) : AbstractDeploymentRule(ruleId) {
    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.itemTypes.mapNotNull { itemType ->
                val typeCode = itemType.deployment?.typeCode?.value?.toIntOrNull() ?: return@mapNotNull null
                if (typeCode !in range) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Deployment typecode '$typeCode' falls in a reserved range.",
                    file = file.path,
                    position = itemType.deployment.typeCode.positionOr(itemType.deployment.location),
                    entityKey = itemType.code.value
                )
            } + file.relations.mapNotNull { relation ->
                val typeCode = relation.deployment?.typeCode?.value?.toIntOrNull() ?: return@mapNotNull null
                if (typeCode !in range) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Deployment typecode '$typeCode' falls in a reserved range.",
                    file = file.path,
                    position = relation.deployment.typeCode.positionOr(relation.deployment.location),
                    entityKey = relation.code.value
                )
            }
        }
    }
}

class TSDeploymentTypeCodeReservedForB2BCommerceExtensionRule : AbstractReservedRangeRule(
    "TSDeploymentTypeCodeReservedForB2BCommerceExtension",
    TYPECODE_MIN_ALLOWED..10099
)

class TSDeploymentTypeCodeReservedForCommonsExtensionRule : AbstractReservedRangeRule(
    "TSDeploymentTypeCodeReservedForCommonsExtension",
    13200..13299
)

class TSDeploymentTypeCodeReservedForLegacyXPrintExtensionRule : AbstractReservedRangeRule(
    "TSDeploymentTypeCodeReservedForLegacyXPrintExtension",
    24400..24599
)

class TSDeploymentTypeCodeReservedForPrintExtensionRule : AbstractReservedRangeRule(
    "TSDeploymentTypeCodeReservedForPrintExtension",
    23400..23999
)

class TSDeploymentTypeCodeReservedForProcessingExtensionRule : AbstractReservedRangeRule(
    "TSDeploymentTypeCodeReservedForProcessingExtension",
    32700..32799
)

class TSDeploymentTypeCodeReservedInspectionRule : AbstractDeploymentRule("TSDeploymentTypeCodeReservedInspection") {
    override fun evaluate(context: RuleContext): List<Finding> {
        val reserved = context.reservedTypeCodes()
        if (reserved.isEmpty()) {
            return emptyList()
        }
        return context.catalog.files.flatMap { file ->
            file.itemTypes.mapNotNull { itemType ->
                val typeCode = itemType.deployment?.typeCode?.value?.toIntOrNull() ?: return@mapNotNull null
                val reservedFor = reserved[typeCode] ?: return@mapNotNull null
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Deployment typecode '$typeCode' is reserved for '$reservedFor'.",
                    file = file.path,
                    position = itemType.deployment.typeCode.positionOr(itemType.deployment.location),
                    entityKey = itemType.code.value
                )
            } + file.relations.mapNotNull { relation ->
                val typeCode = relation.deployment?.typeCode?.value?.toIntOrNull() ?: return@mapNotNull null
                val reservedFor = reserved[typeCode] ?: return@mapNotNull null
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Deployment typecode '$typeCode' is reserved for '$reservedFor'.",
                    file = file.path,
                    position = relation.deployment.typeCode.positionOr(relation.deployment.location),
                    entityKey = relation.code.value
                )
            }
        }
    }
}
