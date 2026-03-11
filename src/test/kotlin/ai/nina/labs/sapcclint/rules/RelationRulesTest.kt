package ai.nina.labs.sapcclint.rules

import ai.nina.labs.sapcclint.catalog.TypeSystemCatalogBuilder
import ai.nina.labs.sapcclint.config.AnalyzerConfig
import ai.nina.labs.sapcclint.itemsxml.parser.ItemsXmlParser
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class RelationRulesTest {

    private val parser = ItemsXmlParser()
    private val catalogBuilder = TypeSystemCatalogBuilder()

    @Test
    fun evaluate_whenBothSidesAreNonNavigable_reportsError() {
        val catalog = createCatalog(
            """
            <items>
              <relations>
                <relation code="Product2Category">
                  <sourceElement type="Product" cardinality="many" navigable="false"/>
                  <targetElement type="Category" cardinality="many" navigable="false"/>
                </relation>
              </relations>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSOnlyOneSideN2mRelationMustBeNotNavigableRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("TSOnlyOneSideN2mRelationMustBeNotNavigable", findings.single().ruleId)
    }

    @Test
    fun evaluate_whenBothSidesAreNavigable_reportsError() {
        val catalog = createCatalog(
            """
            <items>
              <relations>
                <relation code="Product2Category">
                  <sourceElement type="Product" cardinality="many" qualifier="supercategories"/>
                  <targetElement type="Category" cardinality="many" qualifier="products"/>
                </relation>
              </relations>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSOnlyOneSideN2mRelationMustBeNotNavigableRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("TSOnlyOneSideN2mRelationMustBeNotNavigable", findings.single().ruleId)
    }

    @Test
    fun evaluate_whenNonNavigableSideHasQualifier_reportsError() {
        val catalog = createCatalog(
            """
            <items>
              <relations>
                <relation code="Product2Category">
                  <sourceElement type="Product" cardinality="many" qualifier="supercategories"/>
                  <targetElement type="Category" cardinality="many" qualifier="products" navigable="false"/>
                </relation>
              </relations>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSQualifierAndModifiersMustNotBeDeclaredForNavigableFalseRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("TSQualifierAndModifiersMustNotBeDeclaredForNavigableFalse", findings.single().ruleId)
    }

    @Test
    fun evaluate_whenNavigableManyToManySideHasNoQualifier_reportsError() {
        val catalog = createCatalog(
            """
            <items>
              <relations>
                <relation code="Product2Category">
                  <sourceElement type="Product" cardinality="many"/>
                  <targetElement type="Category" cardinality="many" navigable="false"/>
                </relation>
              </relations>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSQualifierMustExistForNavigablePartInN2MRelationRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("TSQualifierMustExistForNavigablePartInN2MRelation", findings.single().ruleId)
    }

    @Test
    fun evaluate_whenManyRelationIsOrdered_reportsWarning() {
        val catalog = createCatalog(
            """
            <items>
              <relations>
                <relation code="Product2Category">
                  <sourceElement type="Product" cardinality="many" qualifier="supercategories" ordered="true"/>
                  <targetElement type="Category" cardinality="one" qualifier="products"/>
                </relation>
              </relations>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSOrderingOfRelationShouldBeAvoidedRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals(FindingSeverity.WARNING, findings.single().severity)
    }

    @Test
    fun evaluate_whenManyRelationUsesList_reportsWarning() {
        val catalog = createCatalog(
            """
            <items>
              <relations>
                <relation code="Product2Category">
                  <sourceElement type="Product" cardinality="many" qualifier="supercategories" collectiontype="list"/>
                  <targetElement type="Category" cardinality="one" qualifier="products"/>
                </relation>
              </relations>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSListsInRelationShouldBeAvoidedRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals(FindingSeverity.WARNING, findings.single().severity)
    }

    private fun createCatalog(content: String) = run {
        val repo = Files.createTempDirectory("sapcc-relation-rules")
        val file = repo.resolve("custom/a/resources/a-items.xml")
        file.parent.createDirectories()
        file.writeText(content)
        catalogBuilder.build(listOf(parser.parse(file)))
    }
}
