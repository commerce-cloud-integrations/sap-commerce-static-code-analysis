package com.cci.sapcclint.catalog

import com.cci.sapcclint.itemsxml.parser.ItemsXmlParser
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TypeSystemCatalogBuilderTest {

    private val parser = ItemsXmlParser()
    private val builder = TypeSystemCatalogBuilder()

    @Test
    fun build_whenFilesContainDeployments_indexesTablesAndTypeCodesAcrossFiles() {
        val repo = Files.createTempDirectory("sapcc-catalog")
        val itemFile = repo.resolve("custom/a/resources/a-items.xml")
        val relationFile = repo.resolve("custom/b/resources/b-items.xml")
        itemFile.parent.createDirectories()
        relationFile.parent.createDirectories()
        itemFile.writeText(
            """
            <items>
              <itemtypes>
                <itemtype code="CustomProduct" extends="GenericItem">
                  <deployment table="products" typecode="12000"/>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )
        relationFile.writeText(
            """
            <items>
              <relations>
                <relation code="Product2Category">
                  <deployment table="products" typecode="15000"/>
                  <sourceElement type="Product"/>
                  <targetElement type="Category"/>
                </relation>
              </relations>
            </items>
            """.trimIndent()
        )

        val catalog = builder.build(listOf(parser.parse(itemFile), parser.parse(relationFile)))

        assertEquals(2, catalog.findDeploymentTable("products").size)
        assertEquals(1, catalog.findDeploymentTypeCode("12000").size)
        assertEquals(1, catalog.findDeploymentTypeCode("15000").size)
    }

    @Test
    fun build_whenClassifiersExist_indexesDeclaredTypesAndCapabilities() {
        val repo = Files.createTempDirectory("sapcc-catalog-classifiers")
        val file = repo.resolve("custom/a/resources/a-items.xml")
        val reserved = repo.resolve("platform/core/resources/core/unittest/reservedTypecodes.txt")
        file.parent.createDirectories()
        reserved.parent.createDirectories()
        reserved.writeText("12345=test\n")
        file.writeText(
            """
            <items>
              <itemtypes>
                <itemtype code="ComposedType"/>
                <itemtype code="AttributeDescriptor"/>
                <itemtype code="RelationDescriptor"/>
                <itemtype code="CustomProduct" extends="GenericItem">
                  <custom-properties>
                    <property name="catalogVersionAttributeQualifier">
                      <value>catalogVersion</value>
                    </property>
                  </custom-properties>
                  <attributes>
                    <attribute qualifier="code" type="java.lang.String"/>
                  </attributes>
                </itemtype>
              </itemtypes>
              <enumtypes>
                <enumtype code="ApprovalStatus">
                  <value code="PENDING"/>
                </enumtype>
              </enumtypes>
              <collectiontypes>
                <collectiontype code="ApprovalStatusList" elementtype="ApprovalStatus" type="list"/>
              </collectiontypes>
              <maptypes>
                <maptype code="StringToStatusMap" argumenttype="java.lang.String" returntype="ApprovalStatus"/>
              </maptypes>
            </items>
            """.trimIndent()
        )

        val catalog = builder.build(listOf(parser.parse(file)))

        assertTrue(catalog.hasLocalDeclaredType("CustomProduct"))
        assertTrue(catalog.hasLocalDeclaredType("ApprovalStatusList"))
        assertEquals(1, catalog.findEnumTypes("ApprovalStatus").size)
        assertEquals(1, catalog.findCollectionTypes("ApprovalStatusList").size)
        assertEquals(1, catalog.findMapTypes("StringToStatusMap").size)
        assertEquals(1, catalog.findDeclaredAttributes("CustomProduct").size)
        assertEquals(1, catalog.findItemCustomPropertiesByName("CustomProduct", "catalogVersionAttributeQualifier").size)
        assertTrue(catalog.hasCapability(AnalysisCapability.LOCAL_TYPE_SYSTEM))
        assertTrue(catalog.hasCapability(AnalysisCapability.PLATFORM_META_TYPES))
        assertTrue(catalog.hasCapability(AnalysisCapability.FULL_REPO_ANCESTRY))
        assertTrue(catalog.hasCapability(AnalysisCapability.PLATFORM_RESERVED_TYPECODES))
        assertNotNull(catalog.repoRoot)
    }

    @Test
    fun build_whenRelationsExist_indexesEndsByType() {
        val repo = Files.createTempDirectory("sapcc-catalog-relations")
        val relationFile = repo.resolve("custom/a/resources/a-items.xml")
        relationFile.parent.createDirectories()
        relationFile.writeText(
            """
            <items>
              <relations>
                <relation code="Product2Category">
                  <sourceElement type="Product" qualifier="supercategories"/>
                  <targetElement type="Category" qualifier="products"/>
                </relation>
              </relations>
            </items>
            """.trimIndent()
        )

        val catalog = builder.build(listOf(parser.parse(relationFile)))

        assertEquals(1, catalog.findRelationEnds("Product").size)
        assertEquals("products", catalog.findRelationEnds("Product").single().declaration.qualifier.value)
        assertEquals(1, catalog.findRelationEnds("Category").size)
        assertEquals("supercategories", catalog.findRelationEnds("Category").single().declaration.qualifier.value)
    }

    @Test
    fun build_whenOppositeSideIsNotNavigable_excludesThatRelationEndFromIndex() {
        val repo = Files.createTempDirectory("sapcc-catalog-nonnav")
        val relationFile = repo.resolve("custom/a/resources/a-items.xml")
        relationFile.parent.createDirectories()
        relationFile.writeText(
            """
            <items>
              <relations>
                <relation code="Product2Category">
                  <sourceElement type="Product" qualifier="supercategories"/>
                  <targetElement type="Category" qualifier="products" navigable="false"/>
                </relation>
              </relations>
            </items>
            """.trimIndent()
        )

        val catalog = builder.build(listOf(parser.parse(relationFile)))

        assertEquals(0, catalog.findRelationEnds("Product").size)
        assertEquals(1, catalog.findRelationEnds("Category").size)
    }

    @Test
    fun findTypeHierarchy_whenParentHasDeploymentAndAttributes_resolvesAncestors() {
        val repo = Files.createTempDirectory("sapcc-catalog-inheritance")
        val file = repo.resolve("custom/a/resources/a-items.xml")
        file.parent.createDirectories()
        file.writeText(
            """
            <items>
              <itemtypes>
                <itemtype code="BaseType" extends="GenericItem">
                  <deployment table="base" typecode="12000"/>
                  <custom-properties>
                    <property name="catalogVersionAttributeQualifier">
                      <value>catalogVersion</value>
                    </property>
                  </custom-properties>
                  <attributes>
                    <attribute qualifier="baseCode" type="java.lang.String"/>
                  </attributes>
                </itemtype>
                <itemtype code="ChildType" extends="BaseType">
                  <attributes>
                    <attribute qualifier="childCode" type="java.lang.String"/>
                  </attributes>
                </itemtype>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val catalog = builder.build(listOf(parser.parse(file)))
        val child = catalog.findItemTypes("ChildType").single()

        val parent = catalog.findNearestDeployedAncestor(child)

        assertNotNull(parent)
        assertEquals("BaseType", parent.declaration.code.value)
        assertEquals(listOf("childCode", "baseCode"), catalog.findAttributes("ChildType", includeAncestors = true).map { it.declaration.qualifier.value })
        assertEquals(1, catalog.findItemCustomPropertiesByName("ChildType", "catalogVersionAttributeQualifier", includeAncestors = true).size)
    }

    @Test
    fun build_whenParentTypeIsMissing_marksFullAncestryCapabilityAsUnavailable() {
        val repo = Files.createTempDirectory("sapcc-catalog-partial")
        val file = repo.resolve("custom/a/resources/a-items.xml")
        file.parent.createDirectories()
        file.writeText(
            """
            <items>
              <itemtypes>
                <itemtype code="ChildType" extends="MissingType"/>
              </itemtypes>
            </items>
            """.trimIndent()
        )

        val catalog = builder.build(listOf(parser.parse(file)))

        assertTrue(!catalog.hasCapability(AnalysisCapability.FULL_REPO_ANCESTRY))
    }
}
