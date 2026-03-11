package ai.nina.labs.sapcclint.rules

import ai.nina.labs.sapcclint.catalog.TypeSystemCatalogBuilder
import ai.nina.labs.sapcclint.config.AnalyzerConfig
import ai.nina.labs.sapcclint.config.CapabilityConfig
import ai.nina.labs.sapcclint.itemsxml.parser.ItemsXmlParser
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class NamingAndTypeRulesTest {

    private val parser = ItemsXmlParser()
    private val catalogBuilder = TypeSystemCatalogBuilder()

    @Test
    fun evaluate_whenTypeCodeStartsLowercase_reportsWarning() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSTypeNameMustStartWithUppercaseLetterRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("TypeNameMustStartWithUppercaseLetter", findings.single().ruleId)
        assertEquals(FindingSeverity.WARNING, findings.single().severity)
    }

    @Test
    fun evaluate_whenTypeCodeStartsWithGenerated_reportsWarning() {
        val catalog = createCatalog(
            """
            <items>
              <enumtypes>
                <enumtype code="GeneratedApprovalStatus">
                  <value code="PENDING"/>
                </enumtype>
              </enumtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSTypeNameMustNotStartWithGeneratedRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("TypeNameMustNotStartWithGenerated", findings.single().ruleId)
        assertEquals(FindingSeverity.WARNING, findings.single().severity)
    }

    @Test
    fun evaluate_whenQualifierStartsUppercase_reportsWarning() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                  <attributes>
                    <attribute qualifier="Code" type="java.lang.String"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSQualifierMustStartWithLowercaseLetterRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("QualifierMustStartWithLowercaseLetter", findings.single().ruleId)
        assertEquals(FindingSeverity.WARNING, findings.single().severity)
    }

    @Test
    fun evaluate_whenEnumValueIsMixedCase_reportsWarning() {
        val catalog = createCatalog(
            """
            <items>
              <enumtypes>
                <enumtype code="ApprovalStatus">
                  <value code="Pending_1"/>
                </enumtype>
              </enumtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSEnumValueMustBeUppercaseRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("TSEnumValueMustBeUppercase", findings.single().ruleId)
        assertEquals(FindingSeverity.WARNING, findings.single().severity)
    }

    @Test
    fun evaluate_whenGenericItemHasNoDeployment_reportsError() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem"/>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSDeploymentTableMustExistForItemExtendingGenericItemRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("DeploymentTableMustExistForItemExtendingGenericItem", findings.single().ruleId)
        assertEquals(FindingSeverity.ERROR, findings.single().severity)
    }

    @Test
    fun evaluate_whenConcreteChildDeclaresDeploymentWithoutGenericItemBase_reportsError() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="BaseType" extends="GenericItem"/>
                <itemtype code="ChildType" extends="BaseType">
                  <deployment table="child" typecode="12001"/>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSNoDeploymentTableShouldExistForItemIfNotExtendingGenericItemRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("NoDeploymentTableShouldExistForItemIfNotExtendingGenericItem", findings.single().ruleId)
    }

    @Test
    fun evaluate_whenAttributeTypeIsMissingInPartialRepo_reportsWarning() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                  <attributes>
                    <attribute qualifier="catalog" type="Catalog"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSTypeNameMustPointToExistingTypeRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("TypeNameMustPointToExistingType", findings.single().ruleId)
        assertEquals(FindingSeverity.WARNING, findings.single().severity)
    }

    @Test
    fun evaluate_whenAttributeTypeIsMissingAndRuleRequiresFullContext_skipsFinding() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                  <attributes>
                    <attribute qualifier="catalog" type="Catalog"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val config = AnalyzerConfig(
            capabilities = CapabilityConfig(requireFullContextFor = listOf("TypeNameMustPointToExistingType"))
        )
        val findings = RuleEngine(listOf(TSTypeNameMustPointToExistingTypeRule())).evaluate(catalog, config)

        assertEquals(0, findings.size)
    }

    @Test
    fun evaluate_whenTypeExistsLocally_reportsNoFinding() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Catalog" extends="GenericItem">
                  <deployment table="catalog" typecode="12000"/>
                </itemtype>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12001"/>
                  <attributes>
                    <attribute qualifier="catalog" type="Catalog"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSTypeNameMustPointToExistingTypeRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(0, findings.size)
    }

    private fun createCatalog(content: String) = run {
        val repo = Files.createTempDirectory("sapcc-type-rules")
        val file = repo.resolve("custom/a/resources/a-items.xml")
        file.parent.createDirectories()
        file.writeText(content)
        catalogBuilder.build(listOf(parser.parse(file)))
    }
}
