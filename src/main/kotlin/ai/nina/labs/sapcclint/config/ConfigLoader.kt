package ai.nina.labs.sapcclint.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads repository configuration from `.sapcc-lint.yml` when present.
 */
class ConfigLoader {

    private val mapper = ObjectMapper(YAMLFactory())
        .registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun load(configPath: Path?): AnalyzerConfig {
        if (configPath == null) {
            return AnalyzerConfig()
        }
        if (!Files.exists(configPath)) {
            return AnalyzerConfig()
        }

        Files.newInputStream(configPath).use { input ->
            return mapper.readValue(input)
        }
    }
}
