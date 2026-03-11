package com.cci.sapcclint.rules

import com.cci.sapcclint.catalog.AnalysisCapability
import com.cci.sapcclint.catalog.findCollectionTypes
import com.cci.sapcclint.catalog.findEnumTypes
import com.cci.sapcclint.catalog.hasLocalDeclaredType
import com.cci.sapcclint.itemsxml.model.AttributeDecl
import com.cci.sapcclint.itemsxml.model.ItemTypeDecl
import com.cci.sapcclint.itemsxml.model.LocatedValue
import com.cci.sapcclint.itemsxml.model.ModifiersDecl
import com.cci.sapcclint.itemsxml.model.PersistenceDecl
import com.cci.sapcclint.itemsxml.model.SourcePosition
import java.nio.file.Path

private val enumDefaultValueRegex = Regex("""^em\(\)\.getEnumerationValue\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)$""")

private fun finding(
    ruleId: String,
    severity: FindingSeverity,
    message: String,
    file: Path,
    position: SourcePosition,
    entityKey: String? = null,
) = Finding(ruleId, severity, message, FindingLocation(file, position), entityKey)

private fun LocatedValue<*>.positionOr(fallback: SourcePosition): SourcePosition = location ?: fallback

private fun ModifiersDecl?.optionalOrDefault(): Boolean = this?.optional?.value ?: true

private fun ModifiersDecl?.initialOrDefault(): Boolean = this?.initial?.value ?: false

private fun ModifiersDecl?.writeOrDefault(): Boolean = this?.write?.value ?: true

private fun ModifiersDecl?.doNotOptimizeOrDefault(): Boolean = this?.doNotOptimize?.value ?: false

private fun PersistenceDecl?.typeOrDefault(): String = this?.type?.value?.lowercase() ?: "property"

private fun RuleContext.hasFullEnumContext(): Boolean =
    analysisMode() != "local" &&
        hasCapability(AnalysisCapability.FULL_REPO_ANCESTRY) &&
        hasCapability(AnalysisCapability.PLATFORM_META_TYPES)

class TSAttributeHandlerMustBeSetForDynamicAttributeRule : TypeSystemRule {
    override val ruleId = "AttributeHandlerMustBeSetForDynamicAttribute"
    override val defaultSeverity = FindingSeverity.ERROR

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.itemTypes.flatMap { itemType ->
                itemType.attributes.mapNotNull { attribute ->
                    val persistence = attribute.persistence ?: return@mapNotNull null
                    if (!persistence.type.value.equals("dynamic", ignoreCase = true) || !persistence.attributeHandler.value.isNullOrBlank()) {
                        return@mapNotNull null
                    }
                    finding(
                        ruleId = ruleId,
                        severity = defaultSeverity,
                        message = "Dynamic attribute '${attribute.qualifier.value ?: "?"}' must declare persistence.attributeHandler.",
                        file = file.path,
                        position = persistence.attributeHandler.location ?: persistence.location,
                        entityKey = itemType.code.value,
                    )
                }
            }
        }
    }
}

class TSCollectionsAreOnlyForDynamicAndJaloRule : TypeSystemRule {
    override val ruleId = "CollectionsAreOnlyForDynamicAndJalo"
    override val defaultSeverity = FindingSeverity.WARNING

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.itemTypes.flatMap { itemType ->
                itemType.attributes.mapNotNull { attribute ->
                    if (context.catalog.findCollectionTypes(attribute.type.value).isEmpty()) {
                        return@mapNotNull null
                    }
                    val persistence = attribute.persistence
                    if (attribute.persistence.typeOrDefault() in setOf("dynamic", "jalo")) {
                        return@mapNotNull null
                    }
                    finding(
                        ruleId = ruleId,
                        severity = defaultSeverity,
                        message = "Collection attribute '${attribute.qualifier.value ?: "?"}' should use dynamic or jalo persistence.",
                        file = file.path,
                        position = persistence?.type?.positionOr(persistence.location) ?: attribute.type.positionOr(attribute.location),
                        entityKey = itemType.code.value,
                    )
                }
            }
        }
    }
}

class TSMandatoryFieldMustHaveInitialValueRule : TypeSystemRule {
    override val ruleId = "MandatoryFieldMustHaveInitialValue"
    override val defaultSeverity = FindingSeverity.WARNING

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.itemTypes.flatMap { itemType ->
                itemType.attributes.mapNotNull { attribute ->
                    if (attribute.modifiers.optionalOrDefault()) {
                        return@mapNotNull null
                    }
                    if (attribute.modifiers.initialOrDefault() || !attribute.defaultValue.value.isNullOrBlank()) {
                        return@mapNotNull null
                    }
                    finding(
                        ruleId = ruleId,
                        severity = defaultSeverity,
                        message = "Mandatory attribute '${attribute.qualifier.value ?: "?"}' should define an initial modifier or default value.",
                        file = file.path,
                        position = attribute.qualifier.positionOr(attribute.location),
                        entityKey = itemType.code.value,
                    )
                }
            }
        }
    }
}

class TSImmutableFieldMustHaveInitialValueRule : TypeSystemRule {
    override val ruleId = "ImmutableFieldMustHaveInitialValue"
    override val defaultSeverity = FindingSeverity.WARNING

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.itemTypes.flatMap { itemType ->
                itemType.attributes.mapNotNull { attribute ->
                    if (attribute.persistence.typeOrDefault() == "dynamic") {
                        return@mapNotNull null
                    }
                    if (attribute.modifiers.writeOrDefault()) {
                        return@mapNotNull null
                    }
                    if (attribute.modifiers.initialOrDefault() && !attribute.defaultValue.value.isNullOrBlank()) {
                        return@mapNotNull null
                    }
                    finding(
                        ruleId = ruleId,
                        severity = defaultSeverity,
                        message = "Immutable attribute '${attribute.qualifier.value ?: "?"}' should define both initial='true' and a default value.",
                        file = file.path,
                        position = attribute.qualifier.positionOr(attribute.location),
                        entityKey = itemType.code.value,
                    )
                }
            }
        }
    }
}

class TSDefaultValueForEnumTypeMustBeAssignableRule : TypeSystemRule {
    override val ruleId = "DefaultValueForEnumTypeMustBeAssignable"
    override val defaultSeverity = FindingSeverity.ERROR

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.itemTypes.flatMap { itemType ->
                itemType.attributes.mapNotNull { attribute ->
                    evaluateEnumDefaultValue(context, file.path, itemType, attribute)
                }
            }
        }
    }

    private fun evaluateEnumDefaultValue(
        context: RuleContext,
        file: Path,
        itemType: ItemTypeDecl,
        attribute: AttributeDecl,
    ): Finding? {
        val defaultValue = attribute.defaultValue.value?.trim() ?: return null
        if (!defaultValue.startsWith("em().getEnumerationValue")) {
            return null
        }

        val match = enumDefaultValueRegex.matchEntire(defaultValue)
            ?: return finding(
                ruleId = ruleId,
                severity = defaultSeverity,
                message = "Default value for enum attribute '${attribute.qualifier.value ?: "?"}' must use em().getEnumerationValue(\"EnumType\", \"EnumValue\").",
                file = file,
                position = attribute.defaultValue.positionOr(attribute.location),
                entityKey = itemType.code.value,
            )

        val expectedEnumType = attribute.type.value ?: return null
        val referencedEnumType = match.groupValues[1]
        val referencedEnumValue = match.groupValues[2]

        if (!referencedEnumType.equals(expectedEnumType, ignoreCase = true)) {
            return finding(
                ruleId = ruleId,
                severity = defaultSeverity,
                message = "Enum default value for '${attribute.qualifier.value ?: "?"}' must reference enum type '$expectedEnumType'.",
                file = file,
                position = attribute.defaultValue.positionOr(attribute.location),
                entityKey = itemType.code.value,
            )
        }

        val localEnums = context.catalog.findEnumTypes(expectedEnumType)
        if (localEnums.isEmpty()) {
            if (context.requiresFullContext(ruleId) && !context.hasFullEnumContext()) {
                return null
            }
            if (!context.hasFullEnumContext() && !context.catalog.hasLocalDeclaredType(expectedEnumType)) {
                return null
            }
            return null
        }

        val enumValues = localEnums.flatMap { it.declaration.values }.mapNotNull { it.code.value }.toSet()
        if (referencedEnumValue in enumValues) {
            return null
        }

        return finding(
            ruleId = ruleId,
            severity = defaultSeverity,
            message = "Enum default value '$referencedEnumValue' is not declared for enum type '$expectedEnumType'.",
            file = file,
            position = attribute.defaultValue.positionOr(attribute.location),
            entityKey = itemType.code.value,
        )
    }
}

class TSCmpPersistanceTypeIsDeprecatedRule : TypeSystemRule {
    override val ruleId = "CmpPersistanceTypeIsDeprecated"
    override val defaultSeverity = FindingSeverity.WARNING

    override fun evaluate(context: RuleContext): List<Finding> {
        return deprecatedPersistenceFindings(context, ruleId, "cmp")
    }
}

class TSJaloPersistanceTypeIsDeprecatedRule : TypeSystemRule {
    override val ruleId = "JaloPersistanceTypeIsDeprecated"
    override val defaultSeverity = FindingSeverity.WARNING

    override fun evaluate(context: RuleContext): List<Finding> {
        return deprecatedPersistenceFindings(context, ruleId, "jalo")
    }
}

private fun deprecatedPersistenceFindings(
    context: RuleContext,
    ruleId: String,
    deprecatedType: String,
): List<Finding> {
    return context.catalog.files.flatMap { file ->
        file.itemTypes.flatMap { itemType ->
            itemType.attributes.mapNotNull { attribute ->
                val persistence = attribute.persistence ?: return@mapNotNull null
                if (!persistence.type.value.equals(deprecatedType, ignoreCase = true)) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = FindingSeverity.WARNING,
                    message = "Persistence type '$deprecatedType' is deprecated for attribute '${attribute.qualifier.value ?: "?"}'.",
                    file = file.path,
                    position = persistence.type.positionOr(persistence.location),
                    entityKey = itemType.code.value,
                )
            }
        }
    }
}

class TSJaloClassIsNotAllowedWhenAddingFieldsToExistingClassRule : TypeSystemRule {
    override val ruleId = "JaloClassIsNotAllowedWhenAddingFieldsToExistingClass"
    override val defaultSeverity = FindingSeverity.ERROR

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.itemTypes.mapNotNull { itemType ->
                if (itemType.jaloClass.value.isNullOrBlank() ||
                    itemType.generate.value != false ||
                    itemType.autoCreate.value != false ||
                    itemType.attributes.isEmpty()
                ) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Item type '${itemType.code.value ?: "?"}' must not declare jaloclass when generate='false' and autocreate='false'.",
                    file = file.path,
                    position = itemType.jaloClass.positionOr(itemType.location),
                    entityKey = itemType.code.value,
                )
            }
        }
    }
}

class TSUseOfUnoptimizedAttributesIsNotRecommendedRule : TypeSystemRule {
    override val ruleId = "UseOfUnoptimizedAttributesIsNotRecommended"
    override val defaultSeverity = FindingSeverity.WARNING

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.itemTypes.flatMap { itemType ->
                itemType.attributes.mapNotNull { attribute ->
                    val modifiers = attribute.modifiers ?: return@mapNotNull null
                    if (!modifiers.doNotOptimizeOrDefault() || modifiers.doNotOptimize.location == null) {
                        return@mapNotNull null
                    }
                    finding(
                        ruleId = ruleId,
                        severity = defaultSeverity,
                        message = "Use of dontOptimize='true' is not recommended for attribute '${attribute.qualifier.value ?: "?"}'.",
                        file = file.path,
                        position = modifiers.doNotOptimize.positionOr(modifiers.location),
                        entityKey = itemType.code.value,
                    )
                }
            }
        }
    }
}
