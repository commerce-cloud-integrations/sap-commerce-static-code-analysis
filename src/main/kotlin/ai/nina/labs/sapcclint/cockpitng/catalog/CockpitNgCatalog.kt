package ai.nina.labs.sapcclint.cockpitng.catalog

import ai.nina.labs.sapcclint.cockpitng.model.CockpitContextDecl
import ai.nina.labs.sapcclint.cockpitng.model.ParsedCockpitFile
import java.nio.file.Path

data class CockpitNgCatalog(
    val files: List<ParsedCockpitFile>,
    val configFiles: List<ParsedCockpitFile>,
    val contextAttributes: Map<String, Set<String>>,
)

data class CockpitContextRecord(
    val file: Path,
    val declaration: CockpitContextDecl,
)

class CockpitNgCatalogBuilder {

    fun build(files: List<ParsedCockpitFile>): CockpitNgCatalog {
        val configFiles = files.filter { it.rootTag == "config" }
        val contextAttributes = mutableMapOf<String, MutableSet<String>>()

        configFiles
            .flatMap { file -> file.contexts.map { CockpitContextRecord(file.path, it) } }
            .flatMap { it.declaration.attributes.entries }
            .mapNotNull { (name, value) -> value.value?.let { resolvedValue -> name to resolvedValue } }
            .forEach { (name, value) ->
                contextAttributes.computeIfAbsent(name) { linkedSetOf() }.add(value)
            }

        return CockpitNgCatalog(
            files = files,
            configFiles = configFiles,
            contextAttributes = contextAttributes.mapValues { it.value.toSet() },
        )
    }
}
