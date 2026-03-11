package ai.nina.labs.sapcclint.impex.parser

import ai.nina.labs.sapcclint.impex.model.ImpexHeaderMode
import ai.nina.labs.sapcclint.impex.model.ImpexParameterSeparator
import ai.nina.labs.sapcclint.impex.model.ImpexReferenceKind
import java.nio.file.Paths
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ImpexParserTest {

    private val parser = ImpexParser()

    @Test
    fun parse_whenImpexContainsMacrosHeadersAndValueLines_buildsSemanticModel() {
        val parsed = parser.parse(fixturePath("complex.impex"))

        assertEquals(2, parsed.macroDeclarations.size)
        assertEquals("\$productCatalog", parsed.macroDeclarations[0].name.value)
        assertEquals(1, parsed.macroDeclarations[0].location.line)
        assertEquals(1, parsed.macroDeclarations[0].location.column)
        assertEquals("\$productCatalogVersion", parsed.macroDeclarations[1].name.value)
        assertEquals("\$productCatalog", parsed.macroDeclarations[1].references.single().text)

        assertEquals(1, parsed.headerBlocks.size)
        val header = parsed.headerBlocks.single().header
        assertEquals(ImpexHeaderMode.INSERT_UPDATE, header.mode)
        assertEquals("Product", header.typeName.value)
        assertEquals("batchmode", header.modifiers.single().name.value)
        assertEquals("true", header.modifiers.single().value.value)
        assertEquals(5, header.parameters.size)
        assertEquals(3, header.location.line)

        val catalogVersion = header.parameters[1]
        assertEquals("catalogVersion", catalogVersion.name.text)
        assertEquals(ImpexReferenceKind.HEADER_PARAMETER, catalogVersion.name.kind)
        assertEquals(2, catalogVersion.leadingParameters.size)
        assertEquals("catalog", catalogVersion.leadingParameters[0].name.text)
        assertEquals("id", catalogVersion.leadingParameters[0].parameters.single().name.text)
        assertEquals("\$productCatalog", catalogVersion.leadingParameters[0].parameters.single().modifiers.single().value.value)
        assertEquals(ImpexParameterSeparator.COMMA, catalogVersion.leadingParameters[1].separator)
        assertEquals("version", catalogVersion.leadingParameters[1].name.text)
        assertEquals("unique", catalogVersion.modifiers.single().name.value)

        val mediaParameter = header.parameters[3]
        assertEquals(ImpexReferenceKind.SPECIAL_PARAMETER, mediaParameter.name.kind)
        assertEquals("translator", mediaParameter.modifiers.single().name.value)
        assertEquals("de.hybris.platform.impex.jalo.media.MediaDataTranslator", mediaParameter.modifiers.single().value.value)
        assertEquals(true, mediaParameter.modifiers.single().valueQuoted)

        val documentId = header.parameters[4]
        assertEquals(ImpexReferenceKind.DOCUMENT_ID, documentId.name.kind)
        assertEquals("&productRef", documentId.name.text)

        val valueLines = parsed.headerBlocks.single().valueLines
        assertEquals(2, valueLines.size)
        assertEquals(null, valueLines[0].subType.value)
        assertEquals("VariantProduct", valueLines[1].subType.value)
        assertEquals(5, valueLines[0].valueGroups.size)
        assertEquals("\$productCatalogVersion", valueLines[0].valueGroups[1].references.single().text)
        assertEquals("&productRef", valueLines[0].valueGroups[4].references.single().text)
        assertEquals("", valueLines[1].valueGroups[3].rawValue)
    }

    @Test
    fun parse_whenValueLinesAppearBeforeAnyHeader_tracksOrphansSeparately() {
        val parsed = parser.parse(fixturePath("orphan-value-lines.impex"))

        assertEquals(1, parsed.orphanValueLines.size)
        assertEquals(1, parsed.headerBlocks.size)
        assertEquals("orphan-code", parsed.orphanValueLines.single().valueGroups[0].rawValue)
        assertEquals("owned-code", parsed.headerBlocks.single().valueLines.single().valueGroups[0].rawValue)
    }

    @Test
    fun parse_whenHeaderParametersContainEmptySegments_tracksSeparatorLocations() {
        val file = kotlin.io.path.createTempFile("sapcc-lint-impex-parser-missing-parameter", ".impex")
        file.writeText(
            """
            INSERT Product;catalogVersion(catalog(id),,version,);code
            ;demo
            """.trimIndent()
        )

        val parsed = parser.parse(file)
        val separators = parsed.headerBlocks.single().header.missingParameterSeparators

        assertEquals(2, separators.size)
        assertEquals(1, separators[0].line)
        assertEquals(42, separators[0].column)
        assertEquals(1, separators[1].line)
        assertEquals(51, separators[1].column)
    }

    private fun fixturePath(name: String) = Paths.get(
        assertNotNull(javaClass.getResource("/impex/$name")).toURI()
    )
}
