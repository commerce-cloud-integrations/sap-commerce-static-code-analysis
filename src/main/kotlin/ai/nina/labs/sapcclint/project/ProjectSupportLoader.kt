package ai.nina.labs.sapcclint.project

import ai.nina.labs.sapcclint.config.AnalyzerConfig
import ai.nina.labs.sapcclint.core.AnalysisDomain
import ai.nina.labs.sapcclint.project.catalog.ExtensionRegistry
import ai.nina.labs.sapcclint.project.catalog.ExtensionRegistryBuilder
import ai.nina.labs.sapcclint.project.model.ParsedExtensionInfoFile
import ai.nina.labs.sapcclint.project.model.ParsedLocalExtensionsFile
import ai.nina.labs.sapcclint.project.parser.ProjectDescriptorParser
import ai.nina.labs.sapcclint.scanner.RepositoryScanner
import java.nio.file.Path

data class ProjectSupportContext(
    val extensionInfoFiles: List<ParsedExtensionInfoFile>,
    val localExtensionsFiles: List<ParsedLocalExtensionsFile>,
    val registry: ExtensionRegistry,
)

class ProjectSupportLoader(
    private val parser: ProjectDescriptorParser = ProjectDescriptorParser(),
    private val registryBuilder: ExtensionRegistryBuilder = ExtensionRegistryBuilder(),
    private val repositoryScanner: RepositoryScanner = RepositoryScanner(),
) {

    fun load(
        repo: Path,
        config: AnalyzerConfig,
        files: List<Path>? = null,
    ): ProjectSupportContext {
        val projectFiles = files ?: repositoryScanner
            .scan(
                repo = repo,
                config = config.copy(domains = config.domains - AnalysisDomain.PROJECT.cliValue),
                requestedDomains = setOf(AnalysisDomain.PROJECT),
            )
            .filesFor(AnalysisDomain.PROJECT)

        val extensionInfoFiles = projectFiles
            .filter { it.fileName.toString() == "extensioninfo.xml" }
            .map(parser::parseExtensionInfo)
        val localExtensionsFiles = projectFiles
            .filter { it.fileName.toString() == "localextensions.xml" }
            .map(parser::parseLocalExtensions)
        val registry = registryBuilder.build(repo, extensionInfoFiles, localExtensionsFiles)

        return ProjectSupportContext(
            extensionInfoFiles = extensionInfoFiles,
            localExtensionsFiles = localExtensionsFiles,
            registry = registry,
        )
    }
}
