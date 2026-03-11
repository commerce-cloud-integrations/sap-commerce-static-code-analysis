package com.cci.sapcclint.config

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigLoaderTest {

    private val loader = ConfigLoader()

    @Test
    fun load_whenConfigMissing_returnsDefaults() {
        val tempDir = Files.createTempDirectory("sapcc-lint-config-missing")
        val config = loader.load(tempDir.resolve(".sapcc-lint.yml"))

        assertEquals("auto", config.analysis.mode)
        assertTrue(config.capabilities.requireFullContextFor.isEmpty())
        assertTrue(config.domains.isEmpty())
        assertTrue(config.paths.exclude.isEmpty())
        assertTrue(config.rules.isEmpty())
    }

    @Test
    fun load_whenConfigExists_readsExcludePatternsAndRules() {
        val tempDir = Files.createTempDirectory("sapcc-lint-config")
        val configPath = tempDir.resolve(".sapcc-lint.yml")
        configPath.writeText(
            """
            analysis:
              mode: local
            capabilities:
              requireFullContextFor:
                - TSTypeNameMustPointToExistingType
            domains:
              impex:
                enabled: false
            paths:
              exclude:
                - custom/excluded/**
            rules:
              TSDeploymentTableMustBeUnique:
                enabled: false
                severity: warning
            """.trimIndent()
        )

        val config = loader.load(configPath)

        assertEquals("local", config.analysis.mode)
        assertEquals(listOf("TSTypeNameMustPointToExistingType"), config.capabilities.requireFullContextFor)
        assertEquals(false, config.domains["impex"]?.enabled)
        assertEquals(listOf("custom/excluded/**"), config.paths.exclude)
        assertEquals(false, config.rules["TSDeploymentTableMustBeUnique"]?.enabled)
        assertEquals("warning", config.rules["TSDeploymentTableMustBeUnique"]?.severity)
    }
}
