package com.cci.sapcclint.rules

import com.cci.sapcclint.catalog.TypeSystemCatalogBuilder
import com.cci.sapcclint.config.AnalyzerConfig
import com.cci.sapcclint.itemsxml.parser.ItemsXmlParser
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class AttributeSemanticsRulesTest {

    private val parser = ItemsXmlParser()
    private val catalogBuilder = TypeSystemCatalogBuilder()

    @Test
    fun evaluate_whenDynamicAttributeHasNoHandler_reportsError() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String">
                      <persistence type="dynamic"/>
                    </attribute>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSAttributeHandlerMustBeSetForDynamicAttributeRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("AttributeHandlerMustBeSetForDynamicAttribute", findings.single().ruleId)
        assertEquals(FindingSeverity.ERROR, findings.single().severity)
    }

    @Test
    fun evaluate_whenCollectionAttributeUsesPropertyPersistence_reportsWarning() {
        val catalog = createCatalog(
            """
            <items>
              <collectiontypes>
                <collectiontype code="StringList" elementtype="java.lang.String" type="list"/>
              </collectiontypes>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                  <attributes>
                    <attribute qualifier="codes" type="StringList">
                      <persistence type="property"/>
                    </attribute>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSCollectionsAreOnlyForDynamicAndJaloRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("CollectionsAreOnlyForDynamicAndJalo", findings.single().ruleId)
        assertEquals(FindingSeverity.WARNING, findings.single().severity)
    }

    @Test
    fun evaluate_whenMandatoryFieldHasNoInitialOrDefault_reportsWarning() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String">
                      <modifiers optional="false"/>
                    </attribute>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSMandatoryFieldMustHaveInitialValueRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("MandatoryFieldMustHaveInitialValue", findings.single().ruleId)
        assertEquals(FindingSeverity.WARNING, findings.single().severity)
    }

    @Test
    fun evaluate_whenImmutableFieldHasNoInitialDefaultPair_reportsWarning() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String">
                      <persistence type="property"/>
                      <modifiers write="false" initial="true"/>
                    </attribute>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSImmutableFieldMustHaveInitialValueRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("ImmutableFieldMustHaveInitialValue", findings.single().ruleId)
        assertEquals(FindingSeverity.WARNING, findings.single().severity)
    }

    @Test
    fun evaluate_whenEnumDefaultExpressionIsMalformed_reportsError() {
        val catalog = createCatalog(
            """
            <items>
              <enumtypes>
                <enumtype code="ApprovalStatus">
                  <value code="PENDING"/>
                </enumtype>
              </enumtypes>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                  <attributes>
                    <attribute qualifier="status" type="ApprovalStatus">
                      <defaultvalue>em().getEnumerationValue("ApprovalStatus")</defaultvalue>
                    </attribute>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSDefaultValueForEnumTypeMustBeAssignableRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("DefaultValueForEnumTypeMustBeAssignable", findings.single().ruleId)
        assertEquals(FindingSeverity.ERROR, findings.single().severity)
    }

    @Test
    fun evaluate_whenEnumDefaultValueIsMissingFromLocalEnum_reportsError() {
        val catalog = createCatalog(
            """
            <items>
              <enumtypes>
                <enumtype code="ApprovalStatus">
                  <value code="PENDING"/>
                </enumtype>
              </enumtypes>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                  <attributes>
                    <attribute qualifier="status" type="ApprovalStatus">
                      <defaultvalue>em().getEnumerationValue("ApprovalStatus", "APPROVED")</defaultvalue>
                    </attribute>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSDefaultValueForEnumTypeMustBeAssignableRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("DefaultValueForEnumTypeMustBeAssignable", findings.single().ruleId)
    }

    @Test
    fun evaluate_whenPersistenceTypeIsCmp_reportsWarning() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String">
                      <persistence type="cmp"/>
                    </attribute>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSCmpPersistanceTypeIsDeprecatedRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("CmpPersistanceTypeIsDeprecated", findings.single().ruleId)
        assertEquals(FindingSeverity.WARNING, findings.single().severity)
    }

    @Test
    fun evaluate_whenPersistenceTypeIsJalo_reportsWarning() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String">
                      <persistence type="jalo"/>
                    </attribute>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSJaloPersistanceTypeIsDeprecatedRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("JaloPersistanceTypeIsDeprecated", findings.single().ruleId)
        assertEquals(FindingSeverity.WARNING, findings.single().severity)
    }

    @Test
    fun evaluate_whenJaloClassIsUsedOnExistingClassExtension_reportsError() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem" jaloclass="com.example.Product" autocreate="false" generate="false">
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSJaloClassIsNotAllowedWhenAddingFieldsToExistingClassRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("JaloClassIsNotAllowedWhenAddingFieldsToExistingClass", findings.single().ruleId)
        assertEquals(FindingSeverity.ERROR, findings.single().severity)
    }

    @Test
    fun evaluate_whenDontOptimizeIsExplicitlyEnabled_reportsWarning() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String">
                      <modifiers dontOptimize="true"/>
                    </attribute>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSUseOfUnoptimizedAttributesIsNotRecommendedRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("UseOfUnoptimizedAttributesIsNotRecommended", findings.single().ruleId)
        assertEquals(FindingSeverity.WARNING, findings.single().severity)
    }

    private fun createCatalog(content: String) = run {
        val repo = Files.createTempDirectory("sapcc-attribute-rules")
        val file = repo.resolve("custom/a/resources/a-items.xml")
        file.parent.createDirectories()
        file.writeText(content)
        catalogBuilder.build(listOf(parser.parse(file)))
    }
}
