package com.cci.sapcclint.rules

import com.cci.sapcclint.catalog.AnalysisCapability
import com.cci.sapcclint.catalog.ItemTypeRecord
import com.cci.sapcclint.catalog.findNearestDeployedAncestor
import com.cci.sapcclint.catalog.findTypeHierarchy
import com.cci.sapcclint.catalog.findItemTypes
import com.cci.sapcclint.catalog.hasLocalDeclaredType
import com.cci.sapcclint.itemsxml.model.ItemTypeDecl
import com.cci.sapcclint.itemsxml.model.LocatedValue
import com.cci.sapcclint.itemsxml.model.SourcePosition
import java.nio.file.Path
import java.util.Locale

private val primitiveTypeNames = setOf("byte", "short", "int", "long", "float", "double", "char", "boolean")
private val wellKnownJavaTypes = setOf(
    "String",
    "java.lang.String",
    "java.io.Serializable",
    "java.math.BigDecimal",
    "java.util.Date",
)
private val genericItemRoots = setOf("GenericItem")
private val descriptorTypeNameRegex = Regex("Descriptor", RegexOption.IGNORE_CASE)

private fun finding(
    ruleId: String,
    severity: FindingSeverity,
    message: String,
    file: Path,
    position: SourcePosition,
    entityKey: String? = null,
) = Finding(ruleId, severity, message, FindingLocation(file, position), entityKey)

private fun LocatedValue<*>.positionOr(fallback: SourcePosition): SourcePosition = location ?: fallback

private fun RuleContext.itemAncestors(itemType: ItemTypeRecord): List<ItemTypeRecord> =
    catalog.findTypeHierarchy(itemType.declaration.code.value, includeSelf = false, includeAncestors = true)

private fun RuleContext.hasFullTypeContext(): Boolean =
    analysisMode() != "local" &&
        hasCapability(AnalysisCapability.FULL_REPO_ANCESTRY) &&
        hasCapability(AnalysisCapability.PLATFORM_META_TYPES)

private fun normalizeTypeName(typeName: String?): String? =
    typeName?.removePrefix("localized:")?.trim()?.takeIf { it.isNotEmpty() }

private fun isKnownNonCatalogType(typeName: String): Boolean {
    return typeName in primitiveTypeNames ||
        typeName in wellKnownJavaTypes ||
        typeName.startsWith("HYBRIS.") ||
        typeName.startsWith("java.") ||
        typeName.startsWith("javax.")
}

private fun candidateTypeReferences(context: RuleContext): List<TypeReferenceCandidate> {
    val collectionTypes = context.catalog.files.flatMap { file ->
        file.collectionTypes.map { declaration ->
            TypeReferenceCandidate(
                typeName = declaration.elementType,
                file = file.path,
                location = declaration.elementType.positionOr(declaration.location),
                entityKey = declaration.code.value,
            )
        }
    }
    val mapTypes = context.catalog.files.flatMap { file ->
        file.mapTypes.flatMap { declaration ->
            listOf(declaration.argumentType, declaration.returnType).map { typeReference ->
                TypeReferenceCandidate(
                    typeName = typeReference,
                    file = file.path,
                    location = typeReference.positionOr(declaration.location),
                    entityKey = declaration.code.value,
                )
            }
        }
    }
    val attributeTypes = context.catalog.files.flatMap { file ->
        file.itemTypes.flatMap { itemType ->
            itemType.attributes.map { attribute ->
                TypeReferenceCandidate(
                    typeName = attribute.type,
                    file = file.path,
                    location = attribute.type.positionOr(attribute.location),
                    entityKey = itemType.code.value,
                )
            }
        }
    }

    return collectionTypes + mapTypes + attributeTypes
}

private data class TypeReferenceCandidate(
    val typeName: LocatedValue<String>,
    val file: Path,
    val location: SourcePosition,
    val entityKey: String?,
)

class TSTypeNameMustStartWithUppercaseLetterRule : TypeSystemRule {
    override val ruleId = "TypeNameMustStartWithUppercaseLetter"
    override val defaultSeverity = FindingSeverity.WARNING

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            val typeDeclarations = file.itemTypes.map { it.code to it.location } +
                file.enumTypes.map { it.code to it.location } +
                file.relations.map { it.code to it.location }

            typeDeclarations.mapNotNull { (code, fallback) ->
                val name = code.value ?: return@mapNotNull null
                if (name.firstOrNull()?.isUpperCase() == true) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Type name '$name' should start with an uppercase letter.",
                    file = file.path,
                    position = code.positionOr(fallback),
                    entityKey = name,
                )
            }
        }
    }
}

class TSTypeNameMustNotStartWithGeneratedRule : TypeSystemRule {
    override val ruleId = "TypeNameMustNotStartWithGenerated"
    override val defaultSeverity = FindingSeverity.WARNING

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            val typeDeclarations = file.itemTypes.map { it.code to it.location } +
                file.enumTypes.map { it.code to it.location } +
                file.relations.map { it.code to it.location }

            typeDeclarations.mapNotNull { (code, fallback) ->
                val name = code.value ?: return@mapNotNull null
                if (!name.startsWith("Generated")) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Type name '$name' must not start with 'Generated'.",
                    file = file.path,
                    position = code.positionOr(fallback),
                    entityKey = name,
                )
            }
        }
    }
}

class TSQualifierMustStartWithLowercaseLetterRule : TypeSystemRule {
    override val ruleId = "QualifierMustStartWithLowercaseLetter"
    override val defaultSeverity = FindingSeverity.WARNING

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            val qualifiers = file.itemTypes.flatMap { itemType ->
                itemType.attributes.map { attribute -> attribute.qualifier to attribute.location to itemType.code.value }
            } + file.relations.flatMap { relation ->
                listOfNotNull(relation.source, relation.target).map { relationEnd ->
                    relationEnd.qualifier to relationEnd.location to relation.code.value
                }
            }

            qualifiers.mapNotNull { (pair, entityKey) ->
                val (qualifier, fallback) = pair
                val name = qualifier.value ?: return@mapNotNull null
                if (name.firstOrNull()?.isLowerCase() == true) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Qualifier '$name' should start with a lowercase letter.",
                    file = file.path,
                    position = qualifier.positionOr(fallback),
                    entityKey = entityKey,
                )
            }
        }
    }
}

class TSEnumValueMustBeUppercaseRule : TypeSystemRule {
    override val ruleId = "TSEnumValueMustBeUppercase"
    override val defaultSeverity = FindingSeverity.WARNING

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.enumTypes.flatMap { enumType ->
                enumType.values.mapNotNull { enumValue ->
                    val rawCode = enumValue.code.value ?: return@mapNotNull null
                    val comparable = rawCode.replace("_", "").replace(Regex("\\d"), "")
                    if (comparable.isEmpty() || comparable == comparable.uppercase(Locale.ROOT)) {
                        return@mapNotNull null
                    }
                    finding(
                        ruleId = ruleId,
                        severity = defaultSeverity,
                        message = "Enum value '$rawCode' should be uppercase.",
                        file = file.path,
                        position = enumValue.code.positionOr(enumValue.location),
                        entityKey = enumType.code.value,
                    )
                }
            }
        }
    }
}

class TSDeploymentTableMustExistForItemExtendingGenericItemRule : TypeSystemRule {
    override val ruleId = "DeploymentTableMustExistForItemExtendingGenericItem"
    override val defaultSeverity = FindingSeverity.ERROR

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.itemTypesByCode.values.flatten().mapNotNull { itemType ->
            val typeName = requiredDeploymentTypeName(context, itemType) ?: return@mapNotNull null
            val declaration = itemType.declaration
            finding(
                ruleId = ruleId,
                severity = defaultSeverity,
                message = "Item type '$typeName' must declare a deployment table and typecode.",
                file = itemType.file,
                position = declaration.code.positionOr(declaration.location),
                entityKey = typeName,
            )
        }
    }

    private fun requiredDeploymentTypeName(
        context: RuleContext,
        itemType: ItemTypeRecord,
    ): String? {
        val declaration = itemType.declaration
        val typeName = declaration.code.value ?: return null
        if (declaration.deployment != null || isDeploymentExempt(declaration, typeName, context, itemType)) {
            return null
        }
        return typeName
    }

    private fun isDeploymentExempt(
        declaration: ItemTypeDecl,
        typeName: String,
        context: RuleContext,
        itemType: ItemTypeRecord,
    ): Boolean {
        if (declaration.isAbstract.value == true || isDeploymentMetaTypeExempt(declaration.metaType.value)) {
            return true
        }
        if (descriptorTypeNameRegex.containsMatchIn(typeName)) {
            return true
        }
        if (context.catalog.findItemTypes(typeName).any { it.declaration.deployment != null && it !== itemType }) {
            return true
        }
        if (context.catalog.findNearestDeployedAncestor(itemType) != null) {
            return true
        }
        val directExtends = declaration.extendsType.value ?: return true
        val ancestors = context.itemAncestors(itemType)
        if (directExtends !in genericItemRoots && ancestors.none { it.declaration.isAbstract.value == true }) {
            return true
        }
        return ancestors.any { descriptorTypeNameRegex.containsMatchIn(it.declaration.code.value.orEmpty()) }
    }

    private fun isDeploymentMetaTypeExempt(metaType: String?): Boolean {
        return metaType?.equals("ViewType", ignoreCase = true) == true ||
            metaType?.equals("ComposedType", ignoreCase = true) == true
    }
}

class TSNoDeploymentTableShouldExistForItemIfNotExtendingGenericItemRule : TypeSystemRule {
    override val ruleId = "NoDeploymentTableShouldExistForItemIfNotExtendingGenericItem"
    override val defaultSeverity = FindingSeverity.ERROR

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.itemTypesByCode.values.flatten().mapNotNull { itemType ->
            val declaration = itemType.declaration
            val deployment = declaration.deployment ?: return@mapNotNull null
            val directExtends = declaration.extendsType.value ?: return@mapNotNull null
            if (directExtends in genericItemRoots) {
                return@mapNotNull null
            }
            if (context.catalog.findNearestDeployedAncestor(itemType) != null) {
                return@mapNotNull null
            }
            val parents = context.catalog.findItemTypes(directExtends)
            if (parents.isEmpty() || parents.any { it.declaration.isAbstract.value == true }) {
                return@mapNotNull null
            }
            finding(
                ruleId = ruleId,
                severity = defaultSeverity,
                message = "Item type '${declaration.code.value ?: "?"}' must not declare deployment when it does not extend GenericItem directly.",
                file = itemType.file,
                position = deployment.location,
                entityKey = declaration.code.value,
            )
        }
    }
}

class TSTypeNameMustPointToExistingTypeRule : TypeSystemRule {
    override val ruleId = "TypeNameMustPointToExistingType"
    override val defaultSeverity = FindingSeverity.ERROR

    override fun evaluate(context: RuleContext): List<Finding> {
        if (context.requiresFullContext(ruleId) && !context.hasFullTypeContext()) {
            return emptyList()
        }

        val severity = if (context.hasFullTypeContext()) FindingSeverity.ERROR else FindingSeverity.WARNING

        return candidateTypeReferences(context).mapNotNull { candidate ->
            val normalized = normalizeTypeName(candidate.typeName.value) ?: return@mapNotNull null
            if (isKnownNonCatalogType(normalized) || context.catalog.hasLocalDeclaredType(normalized)) {
                return@mapNotNull null
            }
            finding(
                ruleId = ruleId,
                severity = severity,
                message = "Type name '$normalized' does not point to a known declared type.",
                file = candidate.file,
                position = candidate.location,
                entityKey = candidate.entityKey,
            )
        }
    }
}
