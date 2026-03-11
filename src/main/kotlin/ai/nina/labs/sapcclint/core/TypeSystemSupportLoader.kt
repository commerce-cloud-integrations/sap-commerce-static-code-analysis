package ai.nina.labs.sapcclint.core

import ai.nina.labs.sapcclint.catalog.TypeSystemCatalog
import ai.nina.labs.sapcclint.catalog.TypeSystemCatalogBuilder
import ai.nina.labs.sapcclint.config.AnalyzerConfig
import ai.nina.labs.sapcclint.itemsxml.model.ParsedItemsFile
import ai.nina.labs.sapcclint.itemsxml.parser.ItemsXmlParser
import ai.nina.labs.sapcclint.scanner.RepositoryScanner
import java.nio.file.Path

data class TypeSystemSupportContext(
    val parsedFiles: List<ParsedItemsFile>,
    val catalog: TypeSystemCatalog,
)

class TypeSystemSupportLoader(
    private val parser: ItemsXmlParser = ItemsXmlParser(),
    private val catalogBuilder: TypeSystemCatalogBuilder = TypeSystemCatalogBuilder(),
    private val repositoryScanner: RepositoryScanner = RepositoryScanner(),
) {

    fun load(
        repo: Path,
        config: AnalyzerConfig,
        files: List<Path>? = null,
    ): TypeSystemSupportContext {
        val typeSystemFiles = files ?: repositoryScanner
            .scan(
                repo = repo,
                config = config.copy(domains = config.domains - AnalysisDomain.TYPE_SYSTEM.cliValue),
                requestedDomains = setOf(AnalysisDomain.TYPE_SYSTEM),
            )
            .filesFor(AnalysisDomain.TYPE_SYSTEM)

        val parsedFiles = typeSystemFiles.map(parser::parse)
        val catalog = catalogBuilder.build(parsedFiles)

        return TypeSystemSupportContext(
            parsedFiles = parsedFiles,
            catalog = catalog,
        )
    }
}
