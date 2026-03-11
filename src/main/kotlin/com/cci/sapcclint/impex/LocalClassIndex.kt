package com.cci.sapcclint.impex

import com.cci.sapcclint.config.AnalyzerConfig
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

data class LocalClassRecord(
    val file: Path,
    val qualifiedName: String,
    val simpleName: String,
    val superTypes: Set<String>,
)

data class LocalClassIndex(
    val classesByQualifiedName: Map<String, LocalClassRecord>,
    val classesBySimpleName: Map<String, List<LocalClassRecord>>,
) {

    fun find(name: String?): List<LocalClassRecord> {
        if (name.isNullOrBlank()) {
            return emptyList()
        }

        val exact = classesByQualifiedName[name]
        if (exact != null) {
            return listOf(exact)
        }

        return classesBySimpleName[name].orEmpty()
    }

    fun resolvesAssignableTo(name: String?, targetTypes: Set<String>): LocalClassResolution {
        val records = find(name)
        if (records.isEmpty()) {
            return LocalClassResolution.UNRESOLVED
        }

        return if (records.any { isAssignableTo(it, targetTypes) }) {
            LocalClassResolution.VALID
        } else {
            LocalClassResolution.INVALID
        }
    }

    private fun isAssignableTo(
        record: LocalClassRecord,
        targetTypes: Set<String>,
        visited: MutableSet<String> = linkedSetOf(),
    ): Boolean {
        if (!visited.add(record.qualifiedName)) {
            return false
        }

        if (record.qualifiedName in targetTypes || record.simpleName in targetTypes.map { it.substringAfterLast('.') }) {
            return true
        }

        val targetSimpleNames = targetTypes.mapTo(linkedSetOf()) { it.substringAfterLast('.') }

        record.superTypes.forEach { superType ->
            if (superType in targetTypes || superType.substringAfterLast('.') in targetSimpleNames) {
                return true
            }

            val nestedMatches = find(superType)
            if (nestedMatches.any { isAssignableTo(it, targetTypes, visited) }) {
                return true
            }
        }

        return false
    }
}

enum class LocalClassResolution {
    VALID,
    INVALID,
    UNRESOLVED,
}

class LocalClassIndexLoader {

    private val defaultExcludes = listOf(
        ".git/**",
        ".gradle/**",
        ".idea/**",
        ".kotlin/**",
        "build/**",
        "out/**",
        "node_modules/**"
    )

    fun load(repo: Path, config: AnalyzerConfig): LocalClassIndex {
        val root = repo.toAbsolutePath().normalize()
        val excludes = buildMatchers(root, defaultExcludes + config.paths.exclude)
        val records = mutableListOf<LocalClassRecord>()

        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .filter { it.fileName.toString().endsWith(".java") || it.fileName.toString().endsWith(".groovy") || it.fileName.toString().endsWith(".kt") }
                .filter { !isExcluded(root, it, excludes) }
                .forEach { file ->
                    records += parseClasses(file)
                }
        }

        val classesByQualifiedName = records.associateBy { it.qualifiedName }
        val classesBySimpleName = records.groupBy { it.simpleName }

        return LocalClassIndex(
            classesByQualifiedName = classesByQualifiedName,
            classesBySimpleName = classesBySimpleName,
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

    private fun parseClasses(file: Path): List<LocalClassRecord> {
        val source = runCatching { Files.readString(file) }.getOrElse { return emptyList() }
        val packageName = packageRegex.find(source)?.groupValues?.get(1).orEmpty()
        val records = mutableListOf<LocalClassRecord>()

        javaLikeDeclarationRegex.findAll(source).forEach { match ->
            val simpleName = match.groupValues[2]
            val superTypes = buildSet {
                addAll(parseSuperTypes(match.groupValues[3]))
                addAll(parseSuperTypes(match.groupValues[4]))
            }
            records += LocalClassRecord(
                file = file,
                qualifiedName = qualifyName(packageName, simpleName),
                simpleName = simpleName,
                superTypes = superTypes,
            )
        }

        kotlinDeclarationRegex.findAll(source).forEach { match ->
            val simpleName = match.groupValues[2]
            if (records.any { it.simpleName == simpleName }) {
                return@forEach
            }

            records += LocalClassRecord(
                file = file,
                qualifiedName = qualifyName(packageName, simpleName),
                simpleName = simpleName,
                superTypes = parseSuperTypes(match.groupValues[3]).toSet(),
            )
        }

        return records
    }

    private fun qualifyName(packageName: String, simpleName: String): String {
        return if (packageName.isBlank()) simpleName else "$packageName.$simpleName"
    }

    private fun parseSuperTypes(raw: String): Set<String> {
        if (raw.isBlank()) {
            return emptySet()
        }

        return raw.split(',')
            .mapNotNull { token ->
                token
                    .substringBefore(" where ")
                    .substringAfterLast(' ')
                    .substringBefore('<')
                    .substringBefore('(')
                    .removePrefix("out ")
                    .removePrefix("in ")
                    .trim()
                    .takeIf { it.isNotEmpty() }
            }
            .toSet()
    }

    companion object {
        private val packageRegex = Regex("""(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)""")
        private val javaLikeDeclarationRegex = Regex(
            """(?m)^\s*(?:public|protected|private|internal|open|abstract|final|sealed|static|data|\s)*\b(class|interface|enum)\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s+extends\s+([A-Za-z0-9_.$<>, ?]+))?(?:\s+implements\s+([A-Za-z0-9_.$<>, ?]+))?"""
        )
        private val kotlinDeclarationRegex = Regex(
            """(?m)^\s*(?:public|protected|private|internal|open|abstract|final|sealed|data|enum\s+)?(class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)(?:\s*\([^)]*\))?(?:\s*:\s*([^{]+))?"""
        )
    }
}
