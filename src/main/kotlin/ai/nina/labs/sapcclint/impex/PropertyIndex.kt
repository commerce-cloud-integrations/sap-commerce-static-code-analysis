package ai.nina.labs.sapcclint.impex

import ai.nina.labs.sapcclint.config.AnalyzerConfig
import java.io.Reader
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.invariantSeparatorsPathString

data class HeaderAbbreviationRule(
    val key: String,
    val pattern: Regex,
    val expansion: String,
)

data class PropertyIndex(
    val valuesByKey: Map<String, List<String>>,
    val headerAbbreviationRules: List<HeaderAbbreviationRule>,
) {
    fun containsKey(key: String): Boolean = valuesByKey.containsKey(key)

    fun supportedLanguages(): Set<String> {
        val configured = valuesByKey["lang.packs"]
            .orEmpty()
            .flatMap { it.split(',') }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(String::lowercase)
            .toMutableSet()
        configured += "en"
        return configured
    }
}

class PropertyIndexLoader {

    private val defaultExcludes = listOf(
        ".git/**",
        ".gradle/**",
        ".idea/**",
        ".kotlin/**",
        "build/**",
        "out/**",
        "node_modules/**"
    )

    fun load(repo: Path, config: AnalyzerConfig): PropertyIndex {
        val root = repo.toAbsolutePath().normalize()
        val excludes = buildMatchers(root, defaultExcludes + config.paths.exclude)
        val valuesByKey = linkedMapOf<String, MutableList<String>>()

        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".properties") }
                .filter { !isExcluded(root, it, excludes) }
                .forEach { file ->
                    parseProperties(file).forEach { (key, value) ->
                        valuesByKey.getOrPut(key) { mutableListOf() } += value
                    }
                }
        }

        val headerAbbreviationRules = valuesByKey.asSequence()
            .filter { (key, _) -> key.startsWith(headerReplacementPropertyPrefix) }
            .flatMap { (key, values) ->
                values.asSequence().mapNotNull { value ->
                    val parts = value.split("...")
                        .map(String::trim)
                        .takeIf { it.size == 2 }
                        ?: return@mapNotNull null
                    val pattern = runCatching { Regex(parts[0].replace("\\\\", "\\")) }.getOrNull() ?: return@mapNotNull null
                    HeaderAbbreviationRule(
                        key = key,
                        pattern = pattern,
                        expansion = parts[1],
                    )
                }
            }
            .toList()

        return PropertyIndex(
            valuesByKey = valuesByKey.mapValues { it.value.toList() },
            headerAbbreviationRules = headerAbbreviationRules,
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

    private fun parseProperties(file: Path): Map<String, String> {
        return runCatching {
            Files.newBufferedReader(file).use(::loadProperties)
        }.getOrDefault(emptyMap())
    }

    private fun loadProperties(reader: Reader): Map<String, String> {
        val properties = Properties()
        properties.load(reader)
        return properties.entries.associate { (key, value) -> key.toString() to value.toString() }
    }

    companion object {
        private const val headerReplacementPropertyPrefix = "impex.header.replacement"
    }
}
