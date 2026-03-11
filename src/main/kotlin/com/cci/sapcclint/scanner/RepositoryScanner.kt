package com.cci.sapcclint.scanner

import com.cci.sapcclint.config.AnalyzerConfig
import com.cci.sapcclint.core.AnalysisDomain
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import kotlin.io.path.invariantSeparatorsPathString

/**
 * Finds SAP Commerce source files to be analyzed in the repository checkout.
 */
class RepositoryScanner {

    private val defaultExcludes = listOf(
        ".git/**",
        ".gradle/**",
        ".idea/**",
        ".kotlin/**",
        "build/**",
        "out/**",
        "node_modules/**"
    )

    fun scan(
        repo: Path,
        config: AnalyzerConfig,
        requestedDomains: Set<AnalysisDomain> = emptySet(),
    ): RepositoryScan {
        val root = repo.toAbsolutePath().normalize()
        val excludes = buildMatchers(root, defaultExcludes + config.paths.exclude)
        val enabledDomains = resolveEnabledDomains(config, requestedDomains)
        val filesByDomain = enabledDomains.associateWith { mutableListOf<Path>() }.toMutableMap()

        Files.walk(root).use { pathStream ->
            pathStream
                .filter { Files.isRegularFile(it) }
                .filter { !isExcluded(root, it, excludes) }
                .forEach { file ->
                    val domain = classify(file, enabledDomains) ?: return@forEach
                    filesByDomain.getValue(domain).add(file.normalize())
                }
        }

        return RepositoryScan(
            repo = root,
            filesByDomain = filesByDomain.mapValues { (_, files) -> files.sorted() },
        )
    }

    private fun buildMatchers(root: Path, patterns: List<String>): List<(Path) -> Boolean> {
        val fileSystem = FileSystems.getDefault()
        return patterns.distinct().map { pattern ->
            val normalizedPattern = pattern.trim().replace("\\", "/")
            val matcher = fileSystem.getPathMatcher("glob:$normalizedPattern")
            return@map { path: Path ->
                val relativePath = root.relativize(path).invariantSeparatorsPathString
                matcher.matches(Path.of(relativePath))
            }
        }
    }

    private fun isExcluded(root: Path, file: Path, excludes: List<(Path) -> Boolean>): Boolean {
        return excludes.any { matcher -> matcher(file) || isParentExcluded(root, file, matcher) }
    }

    private fun isParentExcluded(root: Path, file: Path, matcher: (Path) -> Boolean): Boolean {
        var current = file.parent ?: return false
        while (current.startsWith(root)) {
            if (matcher(current)) {
                return true
            }
            current = current.parent ?: return false
        }
        return false
    }

    private fun resolveEnabledDomains(
        config: AnalyzerConfig,
        requestedDomains: Set<AnalysisDomain>,
    ): Set<AnalysisDomain> {
        val candidateDomains = if (requestedDomains.isEmpty()) AnalysisDomain.entries.toSet() else requestedDomains
        return candidateDomains.filterTo(linkedSetOf()) { domain ->
            config.domains[domain.cliValue]?.enabled != false
        }
    }

    private fun classify(file: Path, enabledDomains: Set<AnalysisDomain>): AnalysisDomain? {
        val fileName = file.fileName.toString()

        if (AnalysisDomain.TYPE_SYSTEM in enabledDomains && fileName.endsWith("-items.xml")) {
            return AnalysisDomain.TYPE_SYSTEM
        }
        if (AnalysisDomain.PROJECT in enabledDomains && (fileName == "extensioninfo.xml" || fileName == "localextensions.xml")) {
            return AnalysisDomain.PROJECT
        }
        if (AnalysisDomain.MANIFEST in enabledDomains && fileName == "manifest.json" && file.parent?.fileName?.toString() == "core-customize") {
            return AnalysisDomain.MANIFEST
        }
        if (AnalysisDomain.IMPEX in enabledDomains && fileName.endsWith(".impex")) {
            return AnalysisDomain.IMPEX
        }
        if (AnalysisDomain.BEAN_SYSTEM in enabledDomains && fileName.endsWith("-beans.xml")) {
            return AnalysisDomain.BEAN_SYSTEM
        }
        if (!fileName.endsWith(".xml")) {
            return null
        }

        return when (readRootTag(file)) {
            "beans" -> AnalysisDomain.BEAN_SYSTEM.takeIf { it in enabledDomains }
            "config", "widgets", "action-definition" -> AnalysisDomain.COCKPIT_NG.takeIf { it in enabledDomains }
            "process" -> AnalysisDomain.BUSINESS_PROCESS.takeIf { it in enabledDomains }
            else -> null
        }
    }

    private fun readRootTag(file: Path): String? {
        return runCatching {
            Files.newInputStream(file).use { input ->
                val reader = xmlInputFactory.createXMLStreamReader(input)
                try {
                    while (reader.hasNext()) {
                        if (reader.next() == XMLStreamConstants.START_ELEMENT) {
                            return@runCatching reader.localName
                        }
                    }
                    null
                } finally {
                    reader.close()
                }
            }
        }.getOrNull()
    }

    companion object {
        private val xmlInputFactory = XMLInputFactory.newFactory().apply {
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
        }
    }
}

data class RepositoryScan(
    val repo: Path,
    val filesByDomain: Map<AnalysisDomain, List<Path>>,
) {
    fun filesFor(domain: AnalysisDomain): List<Path> = filesByDomain[domain].orEmpty()
}
