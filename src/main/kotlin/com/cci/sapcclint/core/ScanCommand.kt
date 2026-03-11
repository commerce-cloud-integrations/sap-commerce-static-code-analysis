package com.cci.sapcclint.core

import com.cci.sapcclint.config.ConfigLoader
import com.cci.sapcclint.reporting.ConsoleReporter
import com.cci.sapcclint.reporting.CsvReporter
import com.cci.sapcclint.reporting.HtmlReporter
import com.cci.sapcclint.reporting.RdjsonlReporter
import com.cci.sapcclint.reporting.SarifReporter
import com.cci.sapcclint.scanner.RepositoryScanner
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Parses scan command arguments and delegates to the analyzer pipeline.
 */
class ScanCommand(
    private val configLoader: ConfigLoader = ConfigLoader(),
    private val repositoryScanner: RepositoryScanner = RepositoryScanner(),
    private val repositoryAnalyzer: RepositoryAnalyzer = RepositoryAnalyzer(),
    private val consoleReporter: ConsoleReporter = ConsoleReporter(),
    private val htmlReporter: HtmlReporter = HtmlReporter(),
    private val csvReporter: CsvReporter = CsvReporter(),
    private val sarifReporter: SarifReporter = SarifReporter(),
    private val rdjsonlReporter: RdjsonlReporter = RdjsonlReporter(),
) {

    fun run(args: List<String>): Int {
        val options = ScanOptionsParser().parse(args)
            ?: return 1

        return try {
            val config = configLoader.load(resolveConfigPath(options))
            val scan = repositoryScanner.scan(options.repo, config, options.domains)
            val result = repositoryAnalyzer.analyze(options.repo, config, scan)

            if (OutputFormat.CONSOLE in options.formats) {
                consoleReporter.write(result, System.out)
            }
            if (OutputFormat.HTML in options.formats) {
                htmlReporter.write(result, requireNotNull(options.htmlOut))
            }
            if (OutputFormat.CSV in options.formats) {
                csvReporter.write(result, requireNotNull(options.csvOut))
            }
            if (OutputFormat.SARIF in options.formats) {
                sarifReporter.write(result, requireNotNull(options.sarifOut))
            }
            if (OutputFormat.RDJSONL in options.formats) {
                rdjsonlReporter.write(result, requireNotNull(options.rdjsonlOut))
            }

            if (result.hasErrors()) 1 else 0
        } catch (exception: Exception) {
            System.err.println("Scan failed: ${exception.message ?: exception::class.simpleName}")
            2
        }
    }

    private fun resolveConfigPath(options: ScanOptions): Path? {
        return options.config ?: options.repo.resolve(".sapcc-lint.yml")
    }
}

data class ScanOptions(
    val repo: Path,
    val config: Path?,
    val formats: Set<OutputFormat>,
    val htmlOut: Path?,
    val csvOut: Path?,
    val sarifOut: Path?,
    val rdjsonlOut: Path?,
    val domains: Set<AnalysisDomain>,
)

enum class OutputFormat {
    CONSOLE,
    HTML,
    CSV,
    SARIF,
    RDJSONL;

    companion object {
        fun fromCliValue(value: String): OutputFormat? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

class ScanOptionsParser {

    fun parse(args: List<String>): ScanOptions? {
        val state = ParserState()
        var index = 0
        while (index < args.size) {
            index = consumeOption(args, index, state) ?: return null
        }

        if (!validateOutputs(state)) {
            return null
        }
        val repo = state.repo ?: run {
            System.err.println("Missing required option: --repo")
            return null
        }

        return ScanOptions(
            repo = repo.normalize(),
            config = state.config?.normalize(),
            formats = state.formats,
            htmlOut = state.htmlOut?.normalize(),
            csvOut = state.csvOut?.normalize(),
            sarifOut = state.sarifOut?.normalize(),
            rdjsonlOut = state.rdjsonlOut?.normalize(),
            domains = state.domains,
        )
    }

    private fun consumeOption(args: List<String>, index: Int, state: ParserState): Int? {
        val token = args[index]
        return when (token) {
            "--repo" -> readPath(args, index + 1, token)?.also { state.repo = it }?.let { index + 2 }
            "--config" -> readPath(args, index + 1, token)?.also { state.config = it }?.let { index + 2 }
            "--format" -> consumeFormat(args, index, state)
            "--html-out" -> readPath(args, index + 1, token)?.also { state.htmlOut = it }?.let { index + 2 }
            "--csv-out" -> readPath(args, index + 1, token)?.also { state.csvOut = it }?.let { index + 2 }
            "--sarif-out" -> readPath(args, index + 1, token)?.also { state.sarifOut = it }?.let { index + 2 }
            "--rdjsonl-out" -> readPath(args, index + 1, token)?.also { state.rdjsonlOut = it }?.let { index + 2 }
            "--domain" -> consumeDomain(args, index, state)
            else -> {
                System.err.println("Unknown scan option: $token")
                null
            }
        }
    }

    private fun consumeFormat(args: List<String>, index: Int, state: ParserState): Int? {
        val value = readValue(args, index + 1, args[index]) ?: return null
        val format = OutputFormat.fromCliValue(value)
        if (format == null) {
            System.err.println("Unsupported format: $value")
            return null
        }
        if (!state.explicitFormatSeen) {
            state.formats.clear()
            state.explicitFormatSeen = true
        }
        state.formats += format
        return index + 2
    }

    private fun consumeDomain(args: List<String>, index: Int, state: ParserState): Int? {
        val value = readValue(args, index + 1, args[index]) ?: return null
        val domain = AnalysisDomain.fromCliValue(value)
        if (domain == null) {
            System.err.println("Unsupported domain: $value")
            return null
        }
        state.domains += domain
        return index + 2
    }

    private fun validateOutputs(state: ParserState): Boolean {
        if (OutputFormat.HTML in state.formats && state.htmlOut == null) {
            System.err.println("Missing required option for HTML output: --html-out")
            return false
        }
        if (OutputFormat.CSV in state.formats && state.csvOut == null) {
            System.err.println("Missing required option for CSV output: --csv-out")
            return false
        }
        if (OutputFormat.SARIF in state.formats && state.sarifOut == null) {
            System.err.println("Missing required option for SARIF output: --sarif-out")
            return false
        }
        if (OutputFormat.RDJSONL in state.formats && state.rdjsonlOut == null) {
            System.err.println("Missing required option for rdjsonl output: --rdjsonl-out")
            return false
        }
        return true
    }

    private fun readPath(args: List<String>, index: Int, option: String): Path? {
        val value = readValue(args, index, option) ?: return null
        return Paths.get(value)
    }

    private fun readValue(args: List<String>, index: Int, option: String): String? {
        if (index >= args.size) {
            System.err.println("Missing value for option: $option")
            return null
        }
        return args[index]
    }

    private data class ParserState(
        var repo: Path? = null,
        var config: Path? = null,
        val formats: LinkedHashSet<OutputFormat> = linkedSetOf(OutputFormat.CONSOLE),
        var explicitFormatSeen: Boolean = false,
        var htmlOut: Path? = null,
        var csvOut: Path? = null,
        var sarifOut: Path? = null,
        var rdjsonlOut: Path? = null,
        val domains: LinkedHashSet<AnalysisDomain> = linkedSetOf(),
    )
}
