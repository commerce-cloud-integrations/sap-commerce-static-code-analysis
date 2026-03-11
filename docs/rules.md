# Rule Reference

This analyzer packages CI-oriented static analysis based on the SAP Commerce IntelliJ IDEA plugin inspection surface from EPAM's repository:
https://github.com/epam/sap-commerce-intellij-idea-plugin

The lists below group the currently supported rule IDs by domain and rule family.

## Type-System Rules

Deployment and typecode rules:

- `DeploymentTableMustExistForItemExtendingGenericItem`
- `NoDeploymentTableShouldExistForItemIfNotExtendingGenericItem`
- `TSDeploymentTableMustBeUnique`
- `TSDeploymentTypeCodeMustBeUnique`
- `TSDeploymentTableMustExistForManyToManyRelation`
- `TSDeploymentTagMustNotBeDeclaredForO2MRelation`
- `TSDeploymentTableMustNotBeRedeclaredInChildTypes`
- `TSDeploymentTableNameLengthShouldBeValid`
- `TSDeploymentTypeCodeMustBeGreaterThanTenThousand`
- `TSDeploymentTypeCodesMustBeGreaterThanTenThousandForRelations`
- `TSDeploymentTypeCodeReservedForB2BCommerceExtension`
- `TSDeploymentTypeCodeReservedForCommonsExtension`
- `TSDeploymentTypeCodeReservedForLegacyXPrintExtension`
- `TSDeploymentTypeCodeReservedForPrintExtension`
- `TSDeploymentTypeCodeReservedForProcessingExtension`
- `TSDeploymentTypeCodeReservedInspection`

Relation rules:

- `TSOnlyOneSideN2mRelationMustBeNotNavigable`
- `TSQualifierAndModifiersMustNotBeDeclaredForNavigableFalse`
- `TSQualifierMustExistForNavigablePartInN2MRelation`
- `TSOrderingOfRelationShouldBeAvoided`
- `TSListsInRelationShouldBeAvoided`

Naming and type-resolution rules:

- `TSEnumValueMustBeUppercase`
- `TypeNameMustStartWithUppercaseLetter`
- `QualifierMustStartWithLowercaseLetter`
- `TypeNameMustNotStartWithGenerated`
- `TypeNameMustPointToExistingType`

Attribute semantics and deprecation rules:

- `AttributeHandlerMustBeSetForDynamicAttribute`
- `CollectionsAreOnlyForDynamicAndJalo`
- `MandatoryFieldMustHaveInitialValue`
- `ImmutableFieldMustHaveInitialValue`
- `DefaultValueForEnumTypeMustBeAssignable`
- `CmpPersistanceTypeIsDeprecated`
- `JaloPersistanceTypeIsDeprecated`
- `JaloClassIsNotAllowedWhenAddingFieldsToExistingClass`
- `UseOfUnoptimizedAttributesIsNotRecommended`

Meta-type and catalog-aware rules:

- `ItemMetaTypeNameMustPointToValidMetaType`
- `ItemAttributeMetaTypeNameMustPointToValidMetaType`
- `RelationElementMetaTypeNameMustPointToValidMetaType`
- `CatalogAwareCatalogVersionAttributeQualifier`
- `CatalogAwareUniqueKeyAttributeQualifier`

## Project Rules

The `project` domain currently validates:

- duplicate required extension declarations
- unknown required extensions when repository context is complete enough
- unknown `localextensions.xml` extension entries when repository context is complete enough

## Manifest Rules

The `manifest` domain currently validates:

- unknown extension references in `core-customize/manifest.json`
- unknown template-extension references in `core-customize/manifest.json`
- unknown extension-pack names in `core-customize/manifest.json`

## ImpEx Rules

Structure and header rules:

- `ImpExMissingHeaderParameterInspection`
- `ImpExMissingValueGroupInspection`
- `ImpExOrphanValueGroupInspection`

Semantic ImpEx checks currently include:

- unknown type names in headers
- unknown attribute paths against the local merged catalog when ancestry is resolvable
- unknown type modifiers and attribute modifiers
- invalid boolean modifier values
- invalid `mode` values
- invalid `disable.interceptor.types` values
- invalid `processor`, `translator`, and `cellDecorator` class references when local source resolution is possible
- unknown macros
- unknown `$config-*` properties
- missing config import processor declarations for config-backed macros
- incomplete header-abbreviation replacement usage
- malformed header parameter separators
- unsupported `lang` values from `lang.packs`
- `lang` modifiers on non-localized attributes
- unknown inline function-reference types and mismatches
- unique attributes without a backing local index
- duplicate document IDs in `&ref` columns
- duplicate unique-key rows overriding earlier non-key values
- non-dynamic enum headers using unsupported modes

Common ImpEx rule IDs you will see in reports include:

- `ImpExUnknownTypeAttributeInspection`
- `ImpExUnknownMacrosInspection`
- `ImpExUnknownConfigPropertyInspection`
- `ImpexNoUniqueValueInspection`
- `ImpexUniqueAttributeWithoutIndex`

## Bean-System Rules

The `bean-system` domain currently validates:

- DOM-style structure issues under `BSDomElementsInspection`
- duplicate bean definitions
- duplicate enum definitions
- duplicate bean property definitions across merged declarations
- duplicate enum values across merged enum declarations
- Java keyword property names
- unescaped `<` and `>` characters in bean property types
- unescaped `>` in bean class names
- removable `java.lang.` prefixes in bean property types

Common bean-system rule IDs include:

- `BSDomElementsInspection`
- `BSDuplicateBeanDefinition`
- `BSDuplicateBeanPropertyDefinition`
- `BSDuplicateEnumDefinition`
- `BSDuplicateEnumValueDefinition`
- `BSUnescapedLessThanSignIsNotAllowedInBeanPropertyType`
- `BSUnescapedGreaterThanSignIsNotAllowedInBeanPropertyType`
- `BSUnescapedGreaterThanSignIsNotAllowedInBeanClassName`
- `BSOmitJavaLangPackageInBeanPropertyType`

## Cockpit NG Rules

The `cockpit-ng` domain currently validates:

- invalid `context/@merge-by` values
- invalid non-`type` parent values for the chosen merge key
- invalid `merge-by="type"` parent chains against locally resolvable item ancestry
- duplicate namespace declarations pointing to the same URI
- non-root namespace declarations that should be hoisted
- DOM-style structure issues for config, embedded actions, and widgets

Common cockpit NG rule IDs include:

- `CngContextMergeByTypeParentIsNotValid`
- `CngNamespaceNotOptimized`
- `CngConfigDomElementsInspection`
- `CngWidgetsDomElementsInspection`

## Business-Process Rules

The `business-process` domain currently validates DOM-style structure issues for process XML, including:

- unsupported children
- unsupported attributes
- missing required attributes
- missing required child nodes
- missing required text content

The core rule family is the business-process DOM inspection surface.

## Severity And Fail Behavior

Rules have built-in default severities, but you can override them in `.sapcc-lint.yml`.

Operationally:

- warnings are reported
- errors are reported and make the scan exit with code `1`
- disabled or `off` rules do not emit findings

See [configuration.md](configuration.md) for severity overrides and rule-level tuning.
