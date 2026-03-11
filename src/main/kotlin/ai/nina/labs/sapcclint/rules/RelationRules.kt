package ai.nina.labs.sapcclint.rules

import ai.nina.labs.sapcclint.itemsxml.model.LocatedValue
import ai.nina.labs.sapcclint.itemsxml.model.RelationDecl
import ai.nina.labs.sapcclint.itemsxml.model.RelationEndDecl
import ai.nina.labs.sapcclint.itemsxml.model.SourcePosition
import java.nio.file.Path

private fun RelationDecl.manyToMany() = source?.cardinalityText() == "many" && target?.cardinalityText() == "many"

private fun RelationDecl.nonNavigableEnds(): List<RelationEndDecl> = listOfNotNull(source, target).filter { it.navigable.value == false }

private fun RelationEndDecl.cardinalityText() = cardinality.value?.lowercase()

private fun LocatedValue<*>.positionOr(fallback: SourcePosition): SourcePosition = location ?: fallback

private fun finding(
    ruleId: String,
    severity: FindingSeverity,
    message: String,
    file: Path,
    position: SourcePosition,
    entityKey: String? = null,
) = Finding(ruleId, severity, message, FindingLocation(file, position), entityKey)

class TSOnlyOneSideN2mRelationMustBeNotNavigableRule : TypeSystemRule {
    override val ruleId = "TSOnlyOneSideN2mRelationMustBeNotNavigable"
    override val defaultSeverity = FindingSeverity.ERROR

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.relations.mapNotNull { relation ->
                if (!relation.manyToMany() || relation.nonNavigableEnds().size == 1) {
                    return@mapNotNull null
                }
                finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "Many-to-many relation '${relation.code.value ?: "?"}' must have exactly one non-navigable side.",
                    file = file.path,
                    position = relation.location,
                    entityKey = relation.code.value
                )
            }
        }
    }
}

class TSQualifierAndModifiersMustNotBeDeclaredForNavigableFalseRule : TypeSystemRule {
    override val ruleId = "TSQualifierAndModifiersMustNotBeDeclaredForNavigableFalse"
    override val defaultSeverity = FindingSeverity.ERROR

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.relations.flatMap { relation ->
                listOfNotNull(relation.source, relation.target).mapNotNull { relationEnd ->
                    if (relationEnd.navigable.value != false) {
                        return@mapNotNull null
                    }
                    if (relationEnd.qualifier.value == null && relationEnd.modifiers == null) {
                        return@mapNotNull null
                    }
                    finding(
                        ruleId = ruleId,
                        severity = defaultSeverity,
                        message = "Non-navigable relation end must not declare qualifier or modifiers.",
                        file = file.path,
                        position = relationEnd.qualifier.location ?: relationEnd.modifiers?.location ?: relationEnd.location,
                        entityKey = relation.code.value
                    )
                }
            }
        }
    }
}

class TSQualifierMustExistForNavigablePartInN2MRelationRule : TypeSystemRule {
    override val ruleId = "TSQualifierMustExistForNavigablePartInN2MRelation"
    override val defaultSeverity = FindingSeverity.ERROR

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.relations.flatMap { relation ->
                if (!relation.manyToMany()) {
                    return@flatMap emptyList()
                }
                listOfNotNull(relation.source, relation.target).mapNotNull { relationEnd ->
                    if (relationEnd.navigable.value == false || relationEnd.qualifier.value != null) {
                        return@mapNotNull null
                    }
                    finding(
                        ruleId = ruleId,
                        severity = defaultSeverity,
                        message = "Navigable many-to-many relation end must declare a qualifier.",
                        file = file.path,
                        position = relationEnd.location,
                        entityKey = relation.code.value
                    )
                }
            }
        }
    }
}

class TSOrderingOfRelationShouldBeAvoidedRule : TypeSystemRule {
    override val ruleId = "TSOrderingOfRelationShouldBeAvoided"
    override val defaultSeverity = FindingSeverity.WARNING

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.relations.flatMap { relation ->
                listOfNotNull(relation.source, relation.target).mapNotNull { relationEnd ->
                    if (relationEnd.cardinalityText() != "many" || relationEnd.ordered.value != true) {
                        return@mapNotNull null
                    }
                    finding(
                        ruleId = ruleId,
                        severity = defaultSeverity,
                        message = "Ordering of relation end '${relationEnd.qualifier.value ?: "?"}' should be avoided.",
                        file = file.path,
                        position = relationEnd.ordered.positionOr(relationEnd.location),
                        entityKey = relation.code.value
                    )
                }
            }
        }
    }
}

class TSListsInRelationShouldBeAvoidedRule : TypeSystemRule {
    override val ruleId = "TSListsInRelationShouldBeAvoided"
    override val defaultSeverity = FindingSeverity.WARNING

    override fun evaluate(context: RuleContext): List<Finding> {
        return context.catalog.files.flatMap { file ->
            file.relations.flatMap { relation ->
                listOfNotNull(relation.source, relation.target).mapNotNull { relationEnd ->
                    if (relationEnd.cardinalityText() != "many" || !relationEnd.collectionType.value.equals("list", ignoreCase = true)) {
                        return@mapNotNull null
                    }
                    finding(
                        ruleId = ruleId,
                        severity = defaultSeverity,
                        message = "List collection type should be avoided for relation end '${relationEnd.qualifier.value ?: "?"}'.",
                        file = file.path,
                        position = relationEnd.collectionType.positionOr(relationEnd.location),
                        entityKey = relation.code.value
                    )
                }
            }
        }
    }
}
