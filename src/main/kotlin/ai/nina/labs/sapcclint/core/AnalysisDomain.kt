package ai.nina.labs.sapcclint.core

enum class AnalysisDomain(
    val cliValue: String,
) {
    TYPE_SYSTEM("type-system"),
    PROJECT("project"),
    MANIFEST("manifest"),
    IMPEX("impex"),
    BEAN_SYSTEM("bean-system"),
    COCKPIT_NG("cockpit-ng"),
    BUSINESS_PROCESS("business-process");

    companion object {
        fun fromCliValue(value: String): AnalysisDomain? = entries.firstOrNull {
            it.cliValue.equals(value, ignoreCase = true)
        }
    }
}
