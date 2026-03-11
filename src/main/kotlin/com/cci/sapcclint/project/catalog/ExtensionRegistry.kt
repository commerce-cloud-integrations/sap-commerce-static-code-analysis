package com.cci.sapcclint.project.catalog

import com.cci.sapcclint.itemsxml.model.LocatedValue
import com.cci.sapcclint.project.model.ParsedExtensionInfoFile
import com.cci.sapcclint.project.model.ParsedLocalExtensionsFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

data class ExtensionInfoRecord(
    val file: Path,
    val name: LocatedValue<String>,
)

data class ExtensionRegistry(
    val repo: Path,
    val extensionInfoFiles: List<ParsedExtensionInfoFile>,
    val localExtensionsFiles: List<ParsedLocalExtensionsFile>,
    val extensionsByName: Map<String, List<ExtensionInfoRecord>>,
) {
    fun findDeclaredExtensions(name: String?): List<ExtensionInfoRecord> = extensionsByName[name.orEmpty().lowercase()].orEmpty()

    fun hasFullPlatformContext(): Boolean {
        val candidates = listOf(
            repo.resolve("bin/platform"),
            repo.resolve("hybris/bin/platform"),
            repo.resolve("platform"),
        )
        return candidates.any(Files::exists) || extensionInfoFiles.any { file ->
            file.path.toString().contains("/bin/modules/") || file.path.toString().contains("/platform/")
        }
    }
}

class ExtensionRegistryBuilder {
    fun build(
        repo: Path,
        extensionInfoFiles: List<ParsedExtensionInfoFile>,
        localExtensionsFiles: List<ParsedLocalExtensionsFile>,
    ): ExtensionRegistry {
        val extensionsByName = extensionInfoFiles
            .filter { it.extensionName.value != null }
            .groupBy { it.extensionName.value!!.lowercase() }
            .mapValues { (_, files) ->
                files.map { parsed ->
                    ExtensionInfoRecord(
                        file = parsed.path,
                        name = parsed.extensionName,
                    )
                }.sortedBy { it.file.name }
            }

        return ExtensionRegistry(
            repo = repo,
            extensionInfoFiles = extensionInfoFiles.sortedBy { it.path.toString() },
            localExtensionsFiles = localExtensionsFiles.sortedBy { it.path.toString() },
            extensionsByName = extensionsByName,
        )
    }
}
