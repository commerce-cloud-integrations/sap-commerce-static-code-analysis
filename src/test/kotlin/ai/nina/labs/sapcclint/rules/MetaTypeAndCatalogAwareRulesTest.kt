package ai.nina.labs.sapcclint.rules

import ai.nina.labs.sapcclint.catalog.TypeSystemCatalogBuilder
import ai.nina.labs.sapcclint.config.AnalyzerConfig
import ai.nina.labs.sapcclint.itemsxml.parser.ItemsXmlParser
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class MetaTypeAndCatalogAwareRulesTest {

    private val parser = ItemsXmlParser()
    private val catalogBuilder = TypeSystemCatalogBuilder()

    @Test
    fun evaluate_whenItemMetaTypeResolvesToWrongDescriptor_reportsError() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="CustomAttributeMeta" extends="AttributeDescriptor"/>
                <itemtype code="Product" extends="GenericItem" metatype="CustomAttributeMeta">
                  <deployment table="product" typecode="12000"/>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSItemMetaTypeNameMustPointToValidMetaTypeRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("ItemMetaTypeNameMustPointToValidMetaType", findings.single().ruleId)
        assertEquals(FindingSeverity.ERROR, findings.single().severity)
    }

    @Test
    fun evaluate_whenUnknownItemMetaTypeCannotBeResolved_skipsFinding() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem" metatype="PlatformComposedSubtype">
                  <deployment table="product" typecode="12000"/>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSItemMetaTypeNameMustPointToValidMetaTypeRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(0, findings.size)
    }

    @Test
    fun evaluate_whenAttributeMetaTypeResolvesToWrongRoot_reportsError() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="CustomRelationMeta" extends="RelationDescriptor"/>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String" metatype="CustomRelationMeta"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSItemAttributeMetaTypeNameMustPointToValidMetaTypeRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("ItemAttributeMetaTypeNameMustPointToValidMetaType", findings.single().ruleId)
    }

    @Test
    fun evaluate_whenRelationMetaTypeResolvesToWrongRoot_reportsError() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="CustomComposedMeta" extends="ComposedType"/>
              </itemtypes>
              <relations>
                <relation code="Product2Category">
                  <sourceElement type="Product" qualifier="supercategories" metatype="CustomComposedMeta"/>
                  <targetElement type="Category" qualifier="products"/>
                </relation>
              </relations>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSRelationElementMetaTypeNameMustPointToValidMetaTypeRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("RelationElementMetaTypeNameMustPointToValidMetaType", findings.single().ruleId)
    }

    @Test
    fun evaluate_whenCatalogVersionQualifierPointsToCatalogVersion_reportsNoFinding() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="CatalogVersion" extends="GenericItem">
                  <deployment table="catalogvers" typecode="12000"/>
                </itemtype>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12001"/>
                  <custom-properties>
                    <property name="catalogVersionAttributeQualifier">
                      <value>catalogVersion</value>
                    </property>
                  </custom-properties>
                  <attributes>
                    <attribute qualifier="catalogVersion" type="CatalogVersion"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSCatalogAwareCatalogVersionAttributeQualifierRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(0, findings.size)
    }

    @Test
    fun evaluate_whenCatalogVersionQualifierPointsToWrongType_reportsError() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                  <custom-properties>
                    <property name="catalogVersionAttributeQualifier">
                      <value>code</value>
                    </property>
                  </custom-properties>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSCatalogAwareCatalogVersionAttributeQualifierRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("CatalogAwareCatalogVersionAttributeQualifier", findings.single().ruleId)
        assertEquals(FindingSeverity.ERROR, findings.single().severity)
    }

    @Test
    fun evaluate_whenCatalogVersionQualifierTypeIsUnknownInPartialRepo_skipsFinding() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                  <custom-properties>
                    <property name="catalogVersionAttributeQualifier">
                      <value>catalogVersion</value>
                    </property>
                  </custom-properties>
                  <attributes>
                    <attribute qualifier="catalogVersion" type="PlatformCatalogVersionSubtype"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSCatalogAwareCatalogVersionAttributeQualifierRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(0, findings.size)
    }

    @Test
    fun evaluate_whenCatalogVersionQualifierIsOnlyPotentiallyInheritedInPartialRepo_skipsFinding() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="ExternalProduct">
                  <custom-properties>
                    <property name="catalogVersionAttributeQualifier">
                      <value>catalogVersion</value>
                    </property>
                  </custom-properties>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSCatalogAwareCatalogVersionAttributeQualifierRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(0, findings.size)
    }

    @Test
    fun evaluate_whenUniqueKeyQualifierIsNotUnique_reportsError() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                  <custom-properties>
                    <property name="uniqueKeyAttributeQualifier">
                      <value>code, catalogVersion</value>
                    </property>
                  </custom-properties>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String">
                      <modifiers unique="true"/>
                    </attribute>
                    <attribute qualifier="catalogVersion" type="java.lang.String"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSCatalogAwareUniqueKeyAttributeQualifierRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("CatalogAwareUniqueKeyAttributeQualifier", findings.single().ruleId)
        assertEquals(FindingSeverity.ERROR, findings.single().severity)
    }

    @Test
    fun evaluate_whenUniqueKeyQualifierIsOnlyPotentiallyInheritedInPartialRepo_skipsFinding() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="ExternalProduct">
                  <custom-properties>
                    <property name="uniqueKeyAttributeQualifier">
                      <value>code</value>
                    </property>
                  </custom-properties>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSCatalogAwareUniqueKeyAttributeQualifierRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(0, findings.size)
    }

    @Test
    fun evaluate_whenUniqueKeyQualifiersAreUnique_reportsNoFinding() {
        val catalog = createCatalog(
            """
            <items>
              <itemtypes>
                <itemtype code="Product" extends="GenericItem">
                  <deployment table="product" typecode="12000"/>
                  <custom-properties>
                    <property name="uniqueKeyAttributeQualifier">
                      <value>code</value>
                    </property>
                  </custom-properties>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String">
                      <modifiers unique="true"/>
                    </attribute>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSCatalogAwareUniqueKeyAttributeQualifierRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(0, findings.size)
    }

    private fun createCatalog(content: String) = run {
        val repo = Files.createTempDirectory("sapcc-meta-rules")
        val file = repo.resolve("custom/a/resources/a-items.xml")
        file.parent.createDirectories()
        file.writeText(content)
        catalogBuilder.build(listOf(parser.parse(file)))
    }
}
