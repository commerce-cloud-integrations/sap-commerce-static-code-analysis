package ai.nina.labs.sapcclint.scanner

import ai.nina.labs.sapcclint.config.AnalyzerConfig
import ai.nina.labs.sapcclint.config.DomainConfig
import ai.nina.labs.sapcclint.config.PathsConfig
import ai.nina.labs.sapcclint.core.AnalysisDomain
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class RepositoryScannerTest {

    private val scanner = RepositoryScanner()

    @Test
    fun scan_whenRepositoryContainsItemsFiles_returnsSortedMatchesForTypeSystemDomain() {
        val tempDir = Files.createTempDirectory("sapcc-lint-repo")
        val fileA = tempDir.resolve("custom/ext-a/resources/a-items.xml")
        val fileB = tempDir.resolve("custom/ext-b/resources/b-items.xml")
        fileA.parent.createDirectories()
        fileB.parent.createDirectories()
        fileA.writeText("<items/>")
        fileB.writeText("<items/>")

        val result = scanner.scan(tempDir, AnalyzerConfig())

        assertEquals(listOf(fileA.normalize(), fileB.normalize()), result.filesFor(AnalysisDomain.TYPE_SYSTEM))
    }

    @Test
    fun scan_whenPathsAreExcluded_skipsMatches() {
        val tempDir = Files.createTempDirectory("sapcc-lint-repo-exclude")
        val included = tempDir.resolve("custom/ext-a/resources/a-items.xml")
        val excluded = tempDir.resolve("custom/excluded/resources/b-items.xml")
        included.parent.createDirectories()
        excluded.parent.createDirectories()
        included.writeText("<items/>")
        excluded.writeText("<items/>")

        val result = scanner.scan(
            tempDir,
            AnalyzerConfig(paths = PathsConfig(exclude = listOf("custom/excluded/**")))
        )

        assertEquals(listOf(included.normalize()), result.filesFor(AnalysisDomain.TYPE_SYSTEM))
    }

    @Test
    fun scan_whenRepositoryContainsMixedFiles_groupsFilesByDomain() {
        val tempDir = Files.createTempDirectory("sapcc-lint-repo-mixed")
        val itemsFile = tempDir.resolve("custom/core/resources/core-items.xml")
        val extensionInfo = tempDir.resolve("custom/core/extensioninfo.xml")
        val localExtensions = tempDir.resolve("config/localextensions.xml")
        val manifest = tempDir.resolve("core-customize/manifest.json")
        val impex = tempDir.resolve("resources/import/projectdata.impex")
        val beans = tempDir.resolve("resources/beans.xml")
        val cockpit = tempDir.resolve("backoffice/config.xml")
        val process = tempDir.resolve("process/order-process.xml")
        listOf(itemsFile, extensionInfo, localExtensions, manifest, impex, beans, cockpit, process).forEach {
            it.parent.createDirectories()
        }
        itemsFile.writeText("<items/>")
        extensionInfo.writeText("<extensioninfo><extension/></extensioninfo>")
        localExtensions.writeText("<hybrisconfig><extensions/></hybrisconfig>")
        manifest.writeText("""{"extensions":["core"]}""")
        impex.writeText("INSERT Product;code[unique=true]\n;demo")
        beans.writeText("<beans><bean class=\"Example\"/></beans>")
        cockpit.writeText("<config/>")
        process.writeText("<process/>")

        val result = scanner.scan(tempDir, AnalyzerConfig())

        assertEquals(listOf(itemsFile.normalize()), result.filesFor(AnalysisDomain.TYPE_SYSTEM))
        assertEquals(
            listOf(localExtensions.normalize(), extensionInfo.normalize()),
            result.filesFor(AnalysisDomain.PROJECT)
        )
        assertEquals(listOf(manifest.normalize()), result.filesFor(AnalysisDomain.MANIFEST))
        assertEquals(listOf(impex.normalize()), result.filesFor(AnalysisDomain.IMPEX))
        assertEquals(listOf(beans.normalize()), result.filesFor(AnalysisDomain.BEAN_SYSTEM))
        assertEquals(listOf(cockpit.normalize()), result.filesFor(AnalysisDomain.COCKPIT_NG))
        assertEquals(listOf(process.normalize()), result.filesFor(AnalysisDomain.BUSINESS_PROCESS))
    }

    @Test
    fun scan_whenDomainIsDisabledByConfig_omitsItsFiles() {
        val tempDir = Files.createTempDirectory("sapcc-lint-repo-disabled-domain")
        val itemsFile = tempDir.resolve("custom/core/resources/core-items.xml")
        itemsFile.parent.createDirectories()
        itemsFile.writeText("<items/>")

        val result = scanner.scan(
            tempDir,
            AnalyzerConfig(domains = mapOf(AnalysisDomain.TYPE_SYSTEM.cliValue to DomainConfig(enabled = false)))
        )

        assertEquals(emptyList(), result.filesFor(AnalysisDomain.TYPE_SYSTEM))
    }
}
