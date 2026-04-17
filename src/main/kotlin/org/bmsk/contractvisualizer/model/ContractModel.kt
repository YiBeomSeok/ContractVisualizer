package org.bmsk.contractvisualizer.model

data class ContractInfo(
    val featureName: String,
    val state: StateDefinition?,
    val events: List<VariantInfo>,
    val effects: List<VariantInfo>,
    val supportingTypes: List<SupportingType>,
)

sealed interface StateDefinition {
    val name: String

    data class DataClassState(
        override val name: String,
        val properties: List<PropertyInfo>,
        val derivedProperties: List<PropertyInfo>,
        val companionConstants: List<PropertyInfo>,
    ) : StateDefinition

    data class SealedState(
        override val name: String,
        val variants: List<VariantInfo>,
    ) : StateDefinition
}

data class PropertyInfo(
    val name: String,
    val type: String,
    val defaultValue: String? = null,
)

data class VariantInfo(
    val name: String,
    val kind: VariantKind,
    val params: List<PropertyInfo> = emptyList(),
    val emitsEffects: List<String> = emptyList(),
    val mutatesFields: List<String> = emptyList(),
)

enum class VariantKind { DATA_OBJECT, DATA_CLASS, OBJECT, ENUM_ENTRY }

data class SupportingType(
    val name: String,
    val kind: SupportingTypeKind,
    val variants: List<VariantInfo>,
)

enum class SupportingTypeKind { ENUM, SEALED_CLASS, SEALED_INTERFACE }
