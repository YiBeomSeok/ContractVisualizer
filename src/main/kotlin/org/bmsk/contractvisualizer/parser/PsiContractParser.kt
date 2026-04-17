package org.bmsk.contractvisualizer.parser

import com.intellij.psi.PsiFile
import org.bmsk.contractvisualizer.model.ContractInfo
import org.bmsk.contractvisualizer.model.PropertyInfo
import org.bmsk.contractvisualizer.model.StateDefinition
import org.bmsk.contractvisualizer.model.SupportingType
import org.bmsk.contractvisualizer.model.SupportingTypeKind
import org.bmsk.contractvisualizer.model.VariantInfo
import org.bmsk.contractvisualizer.model.VariantKind
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty

object PsiContractParser {

    fun parse(psiFile: PsiFile): ContractInfo? {
        val ktFile = psiFile as? KtFile ?: return null
        val declarations = ktFile.declarations.filterIsInstance<KtClass>()

        var state: StateDefinition? = null
        val events = mutableListOf<VariantInfo>()
        val effects = mutableListOf<VariantInfo>()
        val supportingTypes = mutableListOf<SupportingType>()
        var featureName = ""

        for (ktClass in declarations) {
            val name = ktClass.name ?: continue

            when {
                name.endsWith("State") || name.endsWith("UiState") -> {
                    featureName = extractFeatureName(name)
                    state = parseState(ktClass, name)
                }

                name.endsWith("Event") || name.endsWith("UiEvent") -> {
                    if (featureName.isEmpty()) featureName = extractFeatureName(name)
                    events.addAll(parseSealedVariants(ktClass))
                }

                name.endsWith("Effect") || name.endsWith("SideEffect") -> {
                    if (featureName.isEmpty()) featureName = extractFeatureName(name)
                    effects.addAll(parseSealedVariants(ktClass))
                }

                else -> {
                    val supportingType = parseSupportingType(ktClass, name)
                    if (supportingType != null) {
                        supportingTypes.add(supportingType)
                    }
                }
            }
        }

        if (featureName.isEmpty()) return null

        return ContractInfo(
            featureName = featureName,
            state = state,
            events = events,
            effects = effects,
            supportingTypes = supportingTypes,
        )
    }

    private fun extractFeatureName(name: String): String {
        return name
            .removeSuffix("UiState")
            .removeSuffix("State")
            .removeSuffix("UiEvent")
            .removeSuffix("Event")
            .removeSuffix("SideEffect")
            .removeSuffix("Effect")
    }

    private fun parseState(ktClass: KtClass, name: String): StateDefinition? {
        return when {
            ktClass.isData() -> parseDataClassState(ktClass, name)
            ktClass.isSealed() -> StateDefinition.SealedState(name, parseSealedVariants(ktClass))
            else -> null
        }
    }

    private fun parseDataClassState(ktClass: KtClass, name: String): StateDefinition.DataClassState {
        val properties = ktClass.primaryConstructorParameters.map { param ->
            PropertyInfo(
                name = param.name ?: "",
                type = param.typeReference?.text ?: "Any",
                defaultValue = param.defaultValue?.text,
            )
        }

        val body = ktClass.body
        val derivedProperties = parseDerivedProperties(body)
        val companionConstants = parseCompanionConstants(body)

        return StateDefinition.DataClassState(
            name = name,
            properties = properties,
            derivedProperties = derivedProperties,
            companionConstants = companionConstants,
        )
    }

    private fun parseDerivedProperties(body: KtClassBody?): List<PropertyInfo> {
        if (body == null) return emptyList()
        return body.properties
            .filter { prop -> prop.hasDelegate() || prop.getter != null }
            .filter { prop -> !isInCompanion(prop) }
            .map { prop ->
                PropertyInfo(
                    name = prop.name ?: "",
                    type = prop.typeReference?.text ?: "Any",
                )
            }
    }

    private fun isInCompanion(prop: KtProperty): Boolean {
        return prop.parent?.parent is KtObjectDeclaration &&
            (prop.parent?.parent as? KtObjectDeclaration)?.isCompanion() == true
    }

    private fun parseCompanionConstants(body: KtClassBody?): List<PropertyInfo> {
        if (body == null) return emptyList()
        val companion = body.declarations
            .filterIsInstance<KtObjectDeclaration>()
            .firstOrNull { it.isCompanion() }
            ?: return emptyList()

        val companionBody = companion.body ?: return emptyList()
        return companionBody.properties
            .filter { it.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.CONST_KEYWORD) }
            .map { prop ->
                PropertyInfo(
                    name = prop.name ?: "",
                    type = prop.typeReference?.text ?: "Any",
                    defaultValue = prop.initializer?.text,
                )
            }
    }

    private fun parseSealedVariants(ktClass: KtClass): List<VariantInfo> {
        val body = ktClass.body ?: return emptyList()
        val results = mutableListOf<VariantInfo>()

        for (declaration in body.declarations) {
            when (declaration) {
                is KtObjectDeclaration -> {
                    if (!declaration.isCompanion()) {
                        val kind = if (declaration.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.DATA_KEYWORD)) {
                            VariantKind.DATA_OBJECT
                        } else {
                            VariantKind.OBJECT
                        }
                        val (emits, mutates) = extractAnnotations(declaration.annotationEntries)
                        results.add(VariantInfo(declaration.name ?: "", kind, emptyList(), emits, mutates))
                    }
                }

                is KtClass -> {
                    if (declaration.isData()) {
                        val params = declaration.primaryConstructorParameters.map { param ->
                            PropertyInfo(
                                name = param.name ?: "",
                                type = param.typeReference?.text ?: "Any",
                                defaultValue = param.defaultValue?.text,
                            )
                        }
                        val (emits, mutates) = extractAnnotations(declaration.annotationEntries)
                        results.add(VariantInfo(declaration.name ?: "", VariantKind.DATA_CLASS, params, emits, mutates))
                    }
                }
            }
        }

        return results
    }

    private fun extractAnnotations(entries: List<org.jetbrains.kotlin.psi.KtAnnotationEntry>): Pair<List<String>, List<String>> {
        val emitsEffects = mutableListOf<String>()
        val mutatesFields = mutableListOf<String>()

        for (entry in entries) {
            val name = entry.shortName?.asString() ?: continue
            val args = entry.valueArguments.mapNotNull { arg ->
                arg.getArgumentExpression()?.text?.removeSurrounding("\"")
            }

            when (name) {
                "EmitsEffect" -> emitsEffects.addAll(args)
                "MutatesState" -> mutatesFields.addAll(args)
            }
        }

        return emitsEffects to mutatesFields
    }

    private fun parseSupportingType(ktClass: KtClass, name: String): SupportingType? {
        val kind = when {
            ktClass.isEnum() -> SupportingTypeKind.ENUM
            ktClass.isSealed() && ktClass.isInterface() -> SupportingTypeKind.SEALED_INTERFACE
            ktClass.isSealed() -> SupportingTypeKind.SEALED_CLASS
            else -> return null
        }

        val variants = if (kind == SupportingTypeKind.ENUM) {
            parseEnumEntries(ktClass)
        } else {
            parseSealedVariants(ktClass)
        }

        return SupportingType(name, kind, variants)
    }

    private fun parseEnumEntries(ktClass: KtClass): List<VariantInfo> {
        val body = ktClass.body ?: return emptyList()
        return body.declarations
            .filterIsInstance<KtEnumEntry>()
            .map { entry -> VariantInfo(entry.name ?: "", VariantKind.ENUM_ENTRY) }
    }
}
