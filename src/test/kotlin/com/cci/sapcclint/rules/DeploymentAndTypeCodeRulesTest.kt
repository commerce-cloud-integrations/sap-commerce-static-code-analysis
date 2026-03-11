package com.cci.sapcclint.rules

import com.cci.sapcclint.catalog.TypeSystemCatalogBuilder
import com.cci.sapcclint.config.AnalyzerConfig
import com.cci.sapcclint.itemsxml.parser.ItemsXmlParser
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class DeploymentAndTypeCodeRulesTest {

    private val parser = ItemsXmlParser()
    private val catalogBuilder = TypeSystemCatalogBuilder()

    @Test
    fun evaluate_whenDeploymentTableDuplicates_exist_reportsFindings() {
        val catalog = createCatalog(
            "custom/a/resources/a-items.xml" to """
                <items>
                  <itemtypes>
                    <itemtype code="A" extends="GenericItem">
                      <deployment table="shared" typecode="12001"/>
                    </itemtype>
                  </itemtypes>
                </items>
            """.trimIndent(),
            "custom/b/resources/b-items.xml" to """
                <items>
                  <itemtypes>
                    <itemtype code="B" extends="GenericItem">
                      <deployment table="shared" typecode="12002"/>
                    </itemtype>
                  </itemtypes>
                </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSDeploymentTableMustBeUniqueRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(2, findings.size)
        assertEquals("TSDeploymentTableMustBeUnique", findings.first().ruleId)
    }

    @Test
    fun evaluate_whenManyToManyRelationHasNoDeploymentTypeCode_reportsFinding() {
        val catalog = createCatalog(
            "custom/a/resources/a-items.xml" to """
                <items>
                  <relations>
                    <relation code="Product2Category">
                      <deployment table="prod2cat"/>
                      <sourceElement type="Product" cardinality="many"/>
                      <targetElement type="Category" cardinality="many"/>
                    </relation>
                  </relations>
                </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSDeploymentTableMustExistForManyToManyRelationRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("TSDeploymentTableMustExistForManyToManyRelation", findings.single().ruleId)
    }

    @Test
    fun evaluate_whenOneToManyRelationDeclaresDeployment_reportsFinding() {
        val catalog = createCatalog(
            "custom/a/resources/a-items.xml" to """
                <items>
                  <relations>
                    <relation code="Product2Catalog">
                      <deployment table="prod2cat" typecode="15000"/>
                      <sourceElement type="Product" cardinality="one"/>
                      <targetElement type="Catalog" cardinality="many"/>
                    </relation>
                  </relations>
                </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSDeploymentTagMustNotBeDeclaredForO2MRelationRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("TSDeploymentTagMustNotBeDeclaredForO2MRelation", findings.single().ruleId)
    }

    @Test
    fun evaluate_whenChildRedeclaresInheritedDeployment_reportsFinding() {
        val catalog = createCatalog(
            "custom/a/resources/a-items.xml" to """
                <items>
                  <itemtypes>
                    <itemtype code="BaseType" extends="GenericItem">
                      <deployment table="base" typecode="12000"/>
                    </itemtype>
                    <itemtype code="ChildType" extends="BaseType">
                      <deployment table="child" typecode="12001"/>
                    </itemtype>
                  </itemtypes>
                </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSDeploymentTableMustNotBeRedeclaredInChildTypesRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("TSDeploymentTableMustNotBeRedeclaredInChildTypes", findings.single().ruleId)
    }

    @Test
    fun evaluate_whenTypeCodeIsTooLow_reportsFinding() {
        val catalog = createCatalog(
            "custom/a/resources/a-items.xml" to """
                <items>
                  <itemtypes>
                    <itemtype code="A" extends="GenericItem">
                      <deployment table="a" typecode="9999"/>
                    </itemtype>
                  </itemtypes>
                </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSDeploymentTypeCodeMustBeGreaterThanTenThousandRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("TSDeploymentTypeCodeMustBeGreaterThanTenThousand", findings.single().ruleId)
    }

    @Test
    fun evaluate_whenTypeCodeInReservedRange_reportsFinding() {
        val catalog = createCatalog(
            "custom/a/resources/a-items.xml" to """
                <items>
                  <itemtypes>
                    <itemtype code="A" extends="GenericItem">
                      <deployment table="a" typecode="13200"/>
                    </itemtype>
                  </itemtypes>
                </items>
            """.trimIndent()
        )

        val findings = RuleEngine(listOf(TSDeploymentTypeCodeReservedForCommonsExtensionRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("TSDeploymentTypeCodeReservedForCommonsExtension", findings.single().ruleId)
    }

    @Test
    fun evaluate_whenDeploymentTableExceedsConfiguredLength_reportsFinding() {
        val repo = Files.createTempDirectory("sapcc-rules-length")
        repo.resolve("local.properties").writeText("deployment.tablename.maxlength=10\n")
        val file = repo.resolve("custom/a/resources/a-items.xml")
        file.parent.createDirectories()
        file.writeText(
            """
            <items>
              <itemtypes>
                <itemtype code="A" extends="GenericItem">
                  <deployment table="table_name_too_long" typecode="12000"/>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val catalog = catalogBuilder.build(listOf(parser.parse(file)))
        val findings = RuleEngine(listOf(TSDeploymentTableNameLengthShouldBeValidRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("TSDeploymentTableNameLengthShouldBeValid", findings.single().ruleId)
    }

    @Test
    fun evaluate_whenReservedTypeCodesFileExistsInSiblingSubtree_reportsFinding() {
        val repo = Files.createTempDirectory("sapcc-rules-reserved")
        repo.resolve(".git").createDirectories()
        repo.resolve("platform/core/resources/core/unittest").createDirectories()
        repo.resolve("platform/core/resources/core/unittest/reservedTypecodes.txt")
            .writeText("12345=ReservedType\n")
        val file = repo.resolve("custom/a/resources/a-items.xml")
        file.parent.createDirectories()
        file.writeText(
            """
            <items>
              <itemtypes>
                <itemtype code="A" extends="GenericItem">
                  <deployment table="a" typecode="12345"/>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val catalog = catalogBuilder.build(listOf(parser.parse(file)))
        val findings = RuleEngine(listOf(TSDeploymentTypeCodeReservedInspectionRule())).evaluate(catalog, AnalyzerConfig())

        assertEquals(1, findings.size)
        assertEquals("TSDeploymentTypeCodeReservedInspection", findings.single().ruleId)
    }

    private fun createCatalog(vararg files: Pair<String, String>) = run {
        val repo = Files.createTempDirectory("sapcc-rules")
        val parsedFiles = files.map { (relativePath, content) ->
            val file = repo.resolve(relativePath)
            file.parent.createDirectories()
            file.writeText(content)
            parser.parse(file)
        }
        catalogBuilder.build(parsedFiles)
    }
}
