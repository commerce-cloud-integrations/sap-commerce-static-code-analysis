package ai.nina.labs.sapcclint.rules

import ai.nina.labs.sapcclint.catalog.TypeSystemCatalogBuilder
import ai.nina.labs.sapcclint.config.AnalyzerConfig
import ai.nina.labs.sapcclint.config.RuleConfig
import ai.nina.labs.sapcclint.itemsxml.model.SourcePosition
import ai.nina.labs.sapcclint.itemsxml.parser.ItemsXmlParser
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class RuleEngineTest {

    private val parser = ItemsXmlParser()
    private val catalogBuilder = TypeSystemCatalogBuilder()

    @Test
    fun evaluate_whenRuleIsDisabled_skipsItsFindings() {
        val rule = stubRule()
        val findings = RuleEngine(listOf(rule)).evaluate(
            catalog = createCatalog(),
            config = AnalyzerConfig(rules = mapOf(rule.ruleId to RuleConfig(enabled = false)))
        )

        assertEquals(emptyList(), findings)
    }

    @Test
    fun evaluate_whenSeverityIsOverridden_appliesConfiguredSeverity() {
        val rule = stubRule()
        val findings = RuleEngine(listOf(rule)).evaluate(
            catalog = createCatalog(),
            config = AnalyzerConfig(rules = mapOf(rule.ruleId to RuleConfig(severity = "warning")))
        )

        assertEquals(1, findings.size)
        assertEquals(FindingSeverity.WARNING, findings.single().severity)
    }

    @Test
    fun evaluate_whenSeverityIsOff_disablesRuleEvenIfEnabledIsTrue() {
        val rule = stubRule()
        val findings = RuleEngine(listOf(rule)).evaluate(
            catalog = createCatalog(),
            config = AnalyzerConfig(rules = mapOf(rule.ruleId to RuleConfig(enabled = true, severity = "off")))
        )

        assertEquals(emptyList(), findings)
    }

    private fun stubRule() = object : TypeSystemRule {
        override val ruleId = "StubRule"
        override val defaultSeverity = FindingSeverity.ERROR

        override fun evaluate(context: RuleContext): List<Finding> {
            return listOf(
                Finding(
                    ruleId = ruleId,
                    severity = defaultSeverity,
                    message = "stub finding",
                    location = FindingLocation(
                        file = context.catalog.files.first().path,
                        position = SourcePosition(1, 1)
                    )
                )
            )
        }
    }

    private fun createCatalog() = run {
        val repo = Files.createTempDirectory("sapcc-rule-engine")
        val file = repo.resolve("custom/a/resources/a-items.xml")
        file.parent.createDirectories()
        file.writeText(
            """
            <items>
              <itemtypes>
                <itemtype code="BaseType" extends="GenericItem"/>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        catalogBuilder.build(listOf(parser.parse(file)))
    }
}
