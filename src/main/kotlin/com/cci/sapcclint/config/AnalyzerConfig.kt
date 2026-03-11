package com.cci.sapcclint.config

/**
 * User-configurable analyzer settings loaded from repository-local configuration.
 */
data class AnalyzerConfig(
    val analysis: AnalysisConfig = AnalysisConfig(),
    val capabilities: CapabilityConfig = CapabilityConfig(),
    val domains: Map<String, DomainConfig> = emptyMap(),
    val paths: PathsConfig = PathsConfig(),
    val rules: Map<String, RuleConfig> = emptyMap(),
)

data class AnalysisConfig(
    val mode: String = "auto",
)

data class CapabilityConfig(
    val requireFullContextFor: List<String> = emptyList(),
)

data class PathsConfig(
    val exclude: List<String> = emptyList(),
)

data class DomainConfig(
    val enabled: Boolean? = null,
)

data class RuleConfig(
    val enabled: Boolean? = null,
    val severity: String? = null,
)
