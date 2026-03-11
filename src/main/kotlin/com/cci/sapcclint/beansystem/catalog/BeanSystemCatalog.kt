package com.cci.sapcclint.beansystem.catalog

import com.cci.sapcclint.beansystem.model.BeanDecl
import com.cci.sapcclint.beansystem.model.EnumDecl
import com.cci.sapcclint.beansystem.model.ParsedBeansFile
import java.nio.file.Path

data class BeanSystemCatalog(
    val files: List<ParsedBeansFile>,
    val beansByClass: Map<String, List<BeanRecord>>,
    val enumsByClass: Map<String, List<EnumRecord>>,
)

data class BeanRecord(
    val file: Path,
    val declaration: BeanDecl,
)

data class EnumRecord(
    val file: Path,
    val declaration: EnumDecl,
)

class BeanSystemCatalogBuilder {

    fun build(files: List<ParsedBeansFile>): BeanSystemCatalog {
        val beans = files.flatMap { file -> file.beans.map { BeanRecord(file.path, it) } }
        val enums = files.flatMap { file -> file.enums.map { EnumRecord(file.path, it) } }
        return BeanSystemCatalog(
            files = files,
            beansByClass = beans.groupBy { beanKey(it.declaration.clazz.value) },
            enumsByClass = enums.groupBy { it.declaration.clazz.value.orEmpty() },
        )
    }

    private fun beanKey(className: String?): String = className.orEmpty().substringBefore('<')
}
