package org.bmsk.contractvisualizer.mermaid

import org.bmsk.contractvisualizer.model.ContractInfo
import org.bmsk.contractvisualizer.model.PropertyInfo
import org.bmsk.contractvisualizer.model.StateDefinition
import org.bmsk.contractvisualizer.model.VariantInfo

object MermaidGenerator {

    fun generate(contract: ContractInfo): String {
        val sb = StringBuilder()
        sb.appendLine("flowchart LR")

        generateStateSubgraph(sb, contract)
        generateEventsSubgraph(sb, contract)
        generateEffectsSubgraph(sb, contract)
        generateEdges(sb, contract)

        return sb.toString().trimEnd()
    }

    private fun generateStateSubgraph(sb: StringBuilder, contract: ContractInfo) {
        val state = contract.state ?: return

        sb.appendLine("    subgraph State[\"${state.name}\"]")

        when (state) {
            is StateDefinition.DataClassState -> {
                for (prop in state.properties) {
                    val id = "S_${prop.name}"
                    val default = if (prop.defaultValue != null) " = ${prop.defaultValue}" else ""
                    sb.appendLine("        $id[\"${prop.name}: ${escape(prop.type)}${escape(default)}\"]")
                }
                for (prop in state.derivedProperties) {
                    val id = "S_${prop.name}"
                    sb.appendLine("        $id[\"derived ${prop.name}: ${escape(prop.type)}\"]")
                }
                for (prop in state.companionConstants) {
                    val id = "S_const_${prop.name}"
                    val value = if (prop.defaultValue != null) " = ${prop.defaultValue}" else ""
                    sb.appendLine("        $id[\"const ${prop.name}${escape(value)}\"]")
                }
            }

            is StateDefinition.SealedState -> {
                for (variant in state.variants) {
                    val id = "S_${variant.name}"
                    val params = formatParams(variant)
                    sb.appendLine("        $id[\"${variant.name}${escape(params)}\"]")
                }
            }
        }

        sb.appendLine("    end")
    }

    private fun generateEventsSubgraph(sb: StringBuilder, contract: ContractInfo) {
        if (contract.events.isEmpty()) return

        sb.appendLine("    subgraph Events[\"${contract.featureName}Event\"]")
        for (event in contract.events) {
            val id = "E_${event.name}"
            val params = formatParams(event)
            sb.appendLine("        $id[\"${event.name}${escape(params)}\"]")
        }
        sb.appendLine("    end")
    }

    private fun generateEffectsSubgraph(sb: StringBuilder, contract: ContractInfo) {
        if (contract.effects.isEmpty()) return

        sb.appendLine("    subgraph Effects[\"${contract.featureName}Effect\"]")
        for (effect in contract.effects) {
            val id = "EF_${effect.name}"
            val params = formatParams(effect)
            sb.appendLine("        $id[\"${effect.name}${escape(params)}\"]")
        }
        sb.appendLine("    end")
    }

    private fun generateEdges(sb: StringBuilder, contract: ContractInfo) {
        if (contract.state == null) return

        val effectNameToIndex = contract.effects.withIndex().associate { (_, v) -> v.name to v }

        for (event in contract.events) {
            if (event.mutatesFields.isNotEmpty()) {
                val label = event.mutatesFields.joinToString(", ")
                sb.appendLine("    E_${event.name} -->|${escape(label)}| State")
            } else {
                sb.appendLine("    E_${event.name} -.-> State")
            }

            if (event.emitsEffects.isNotEmpty()) {
                for (effectName in event.emitsEffects) {
                    if (effectName in effectNameToIndex) {
                        sb.appendLine("    E_${event.name} --> EF_$effectName")
                    }
                }
            } else {
                for (effect in contract.effects) {
                    if (namesMatch(event.name, effect.name)) {
                        sb.appendLine("    E_${event.name} --> EF_${effect.name}")
                    }
                }
            }
        }
    }

    private fun namesMatch(eventName: String, effectName: String): Boolean {
        val normalizedEvent = eventName.removePrefix("On")
        if (normalizedEvent == effectName || effectName.contains(normalizedEvent) || normalizedEvent.contains(effectName)) return true
        val eventWords = splitCamelCase(normalizedEvent).toSet()
        val effectWords = splitCamelCase(effectName).toSet()
        val commonWords = eventWords.intersect(effectWords) - setOf("to", "on", "is", "get", "set")
        return commonWords.size >= 2
    }

    private fun splitCamelCase(name: String): List<String> {
        return Regex("[A-Z][a-z]*").findAll(name).map { it.value.lowercase() }.toList()
    }

    private fun formatParams(variant: VariantInfo): String {
        if (variant.params.isEmpty()) return ""
        val paramStr = variant.params.joinToString(", ") { "${it.name}: ${it.type}" }
        return "($paramStr)"
    }

    private fun escape(text: String): String {
        return text.replace("\"", "'")
    }
}
