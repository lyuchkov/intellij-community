// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.highlighting

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInsight.daemon.impl.Divider
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.UnusedSymbolUtil
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFixAndIntentionActionOnPsiElement
import com.intellij.codeInspection.SuppressionUtil
import com.intellij.codeInspection.deadCode.UnusedDeclarationInspectionBase
import com.intellij.codeInspection.ex.InspectionProfileWrapper
import com.intellij.codeInspection.util.IntentionName
import com.intellij.concurrency.JobLauncher
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Predicates
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.createSmartPointer
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.refactoring.safeDelete.SafeDeleteHandler
import org.jetbrains.annotations.Nls
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.*
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.idea.base.analysis.KotlinUastOutOfCodeBlockModificationTracker
import org.jetbrains.kotlin.idea.base.highlighting.KotlinBaseHighlightingBundle
import org.jetbrains.kotlin.idea.codeinsights.impl.base.isExplicitlyIgnoredByName
import org.jetbrains.kotlin.idea.highlighting.analyzers.isCalleeExpression
import org.jetbrains.kotlin.idea.highlighting.analyzers.isConstructorCallReference
import org.jetbrains.kotlin.idea.inspections.describe
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.DataClassResolver

internal class KotlinUnusedHighlightingProcessor(private val ktFile: KtFile) {
    private val enabled: Boolean
    private val deadCodeKey: HighlightDisplayKey?
    private val deadCodeInspection: LocalInspectionTool?
    private val deadCodeInfoType: HighlightInfoType.HighlightInfoTypeImpl?
    private val refHolder:KotlinRefsHolder = KotlinRefsHolder(ktFile)
    private val javaInspection: UnusedDeclarationInspectionBase = UnusedDeclarationInspectionBase()

    init {
        val project = ktFile.project
        val profile = InspectionProjectProfileManager.getInstance(project).getCurrentProfile().let {
            InspectionProfileWrapper.getCustomInspectionProfileWrapper(ktFile)?.apply(it)?.inspectionProfile ?: it
        }
        deadCodeKey = HighlightDisplayKey.find("UnusedSymbol")
        deadCodeInspection = profile.getUnwrappedTool("UnusedSymbol", ktFile) as? LocalInspectionTool

        deadCodeInfoType = if (deadCodeKey == null) {
            null
        } else {
            val editorAttributes = profile.getEditorAttributes(deadCodeKey.shortName, ktFile)
            HighlightInfoType.HighlightInfoTypeImpl(
                profile.getErrorLevel(deadCodeKey, ktFile).severity,
                editorAttributes ?: HighlightInfoType.UNUSED_SYMBOL.getAttributesKey()
            )
        }
        enabled = deadCodeInspection != null
                && deadCodeInfoType != null
                && profile.isToolEnabled(deadCodeKey, ktFile)
    }

    internal fun collectHighlights(holder: HighlightInfoHolder) {
        if (!enabled) return

        Divider.divideInsideAndOutsideAllRoots(ktFile, ktFile.textRange, holder.annotationSession.priorityRange, Predicates.alwaysTrue()) { dividedElements ->
            analyze(ktFile) {
                registerLocalReferences(dividedElements.inside())
                registerLocalReferences(dividedElements.outside())
            }

            // highlight visible symbols first
            collectAndHighlightNamedElements(dividedElements.inside(), holder)
            collectAndHighlightNamedElements(dividedElements.outside(), holder)
            true
        }
    }

    context(KaSession)
    @OptIn(KaExperimentalApi::class)
    private fun registerLocalReferences(elements: List<PsiElement>) {
        val registerDeclarationAccessVisitor = object : KtVisitorVoid() {
            override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                if (expression.parent is KtValueArgumentName) {
                    // usage of parameter in form of named argument is not counted
                    return
                }
                val resolvedSymbol =
                    expression.resolveToCall()?.successfulCallOrNull<KaCallableMemberCall<*, *>>()?.partiallyAppliedSymbol
                resolvedSymbol?.contextArguments?.forEach {
                    refHolder.registerLocalRef(((it as? KaImplicitReceiverValue)?.symbol as? KaContextParameterSymbol)?.psi)
                }

                val symbol = resolvedSymbol?.symbol ?: expression.mainReference.resolveToSymbol()
                if (symbol is KaLocalVariableSymbol || symbol is KaValueParameterSymbol || symbol is KaKotlinPropertySymbol || symbol is KaContextParameterSymbol) {
                    refHolder.registerLocalRef(symbol.psi)
                }
                if (!expression.isCalleeExpression()) {
                    val parent = expression.parent

                    if (parent is KtInstanceExpressionWithLabel) {
                        // Do nothing: 'super' and 'this' are highlighted as a keyword
                        return
                    }
                    if (expression.isConstructorCallReference()) {
                        refHolder.registerLocalRef((expression.mainReference.resolveToSymbol() as? KaConstructorSymbol)?.psi)
                    }
                    else if (expression is KtOperationReferenceExpression) {
                        refHolder.registerLocalRef(symbol?.psi)
                    }
                    else if (symbol is KaClassifierSymbol) {
                        refHolder.registerLocalRef(symbol.psi)
                    }
                }
            }

            override fun visitBinaryExpression(expression: KtBinaryExpression) {
                val call = expression.resolveToCall()?.successfulCallOrNull<KaCall>() ?: return
                if (call is KaSimpleFunctionCall) {
                    refHolder.registerLocalRef(call.symbol.psi)
                }
                if (call is KaCompoundAccessCall) {
                    refHolder.registerLocalRef(call.compoundOperation.operationPartiallyAppliedSymbol.symbol.psi)
                }
            }

            override fun visitCallableReferenceExpression(expression: KtCallableReferenceExpression) {
                val symbol = expression.callableReference.mainReference.resolveToSymbol() ?: return
                refHolder.registerLocalRef(symbol.psi)
            }

            override fun visitCallExpression(expression: KtCallExpression) {
                val callee = expression.calleeExpression ?: return
                val call = expression.resolveToCall()?.singleCallOrNull<KaCall>() ?: return
                if (callee is KtLambdaExpression || callee is KtCallExpression /* KT-16159 */) return
                refHolder.registerLocalRef((call as? KaSimpleFunctionCall)?.symbol?.psi)
                if (call is KaSimpleFunctionCall && call.isImplicitInvoke) {
                    call.partiallyAppliedSymbol.contextArguments.forEach {
                        refHolder.registerLocalRef(((it as? KaImplicitReceiverValue)?.symbol as? KaContextParameterSymbol)?.psi)
                    }
                }
            }

            override fun visitArrayAccessExpression(expression: KtArrayAccessExpression) {
                expression.references.forEach { reference ->
                    reference.resolve()?.let {
                        refHolder.registerLocalRef(it)
                    }
                }
            }
        }
        for (declaration in elements) {
            declaration.accept(registerDeclarationAccessVisitor)
        }
    }

    private fun collectAndHighlightNamedElements(psiElements: List<PsiElement>, holder: HighlightInfoHolder) {
        val namedElements: MutableList<KtNamedDeclaration> = mutableListOf()
        val namedElementVisitor = object : KtVisitorVoid() {
            override fun visitNamedDeclaration(declaration: KtNamedDeclaration) {
                if (declaration.isExplicitlyIgnoredByName()) return
                namedElements.add(declaration)
            }
        }
        for (declaration in psiElements) {
            declaration.accept(namedElementVisitor)
        }
        JobLauncher.getInstance()
            .invokeConcurrentlyUnderProgress(namedElements, ProgressManager.getGlobalProgressIndicator()) { declaration ->
                handleDeclaration(declaration, deadCodeInspection!!, deadCodeInfoType!!, deadCodeKey!!, holder)
                true
            }
    }

    private fun handleDeclaration(declaration: KtNamedDeclaration,
                                  deadCodeInspection: LocalInspectionTool,
                                  deadCodeInfoType: HighlightInfoType.HighlightInfoTypeImpl,
                                  deadCodeKey: HighlightDisplayKey,
                                  holder: HighlightInfoHolder) {
        if (!K2UnusedSymbolUtil.isApplicableByPsi(declaration)) return
        if (refHolder.isUsedLocally(declaration)) return // even for non-private declarations our refHolder might have usage info
        val mustBeLocallyReferenced = declaration is KtParameter && !declaration.hasValOrVar()
                                               && (declaration.parent?.parent as? KtModifierListOwner)?.hasModifier(KtTokens.EXTERNAL_KEYWORD) != true // parameters of external functions might be referenced elsewhere
                || declaration.hasModifier(KtTokens.PRIVATE_KEYWORD)
                   // "List.component6" declaration will be checked by reference search, since there's no psi which can be `ctrl-b`ed to this declaration
                   && declaration.name?.let { DataClassResolver.isComponentLike(it) } != true
                || ((declaration.parent as? KtClassBody)?.parent as? KtClassOrObject)?.isLocal == true
        if (SuppressionUtil.inspectionResultSuppressed(declaration, deadCodeInspection)) {
            return
        }
        val problemPsiElement =
            CachedValuesManager.getCachedValue(declaration) {
                val element = analyze(declaration) {
                    if (K2UnusedSymbolUtil.isHiddenFromResolution(declaration)) return@analyze null
                    val nameIdentifier = declaration.nameIdentifier
                    if (mustBeLocallyReferenced
                        && declaration.annotationEntries.isEmpty() //instead of slow implicit usages checks
                        && declaration !is KtClass // look globally for private classes too, since they could be referenced from some fancy .xml
                        && (((declaration as? KtParameter)?.parent?.parent as? KtAnnotated)?.annotationEntries?.isEmpty() != false)
                    ) {
                        nameIdentifier ?: (declaration as? KtConstructor<*>)?.getConstructorKeyword() ?: declaration
                    } else {
                        K2UnusedSymbolUtil.getPsiToReportProblem(declaration, javaInspection)
                    }?.createSmartPointer()
                }
                CachedValueProvider.Result.create(
                    element,
                    KotlinUastOutOfCodeBlockModificationTracker.getInstance(project = declaration.project)
                )
            }?.element ?: return
        val description = declaration.describe() ?: return
        val message = KotlinBaseHighlightingBundle.message("inspection.message.never.used", description)
        val builder = UnusedSymbolUtil.createUnusedSymbolInfoBuilder(problemPsiElement, message, deadCodeInfoType, null)
        val fixes = K2UnusedSymbolUtil.createQuickFixes(declaration)
        fixes.forEach { builder.registerFix(it, null, null, null, deadCodeKey) }
        holder.add(builder.create())
    }
}

class KotlinRefsHolder(val containingFile: KtFile) {
    private val localRefs = mutableSetOf<KtDeclaration>()

    fun registerLocalRef(declaration: PsiElement?) {
        if (declaration is KtDeclaration && declaration.containingFile == containingFile) {
            localRefs += declaration
        }
    }

    fun isUsedLocally(declaration: KtDeclaration): Boolean {
        if (localRefs.contains(declaration)) {
            return true
        }

        if (declaration is KtClass) {
            return declaration.primaryConstructor?.let { localRefs.contains(it) } == true ||
                    declaration.secondaryConstructors.any { localRefs.contains(it) }
        }
        return false
    }
}

internal class SafeDeleteFix(declaration: KtNamedDeclaration) : LocalQuickFixAndIntentionActionOnPsiElement(declaration) {
    @Nls
    private val name: String =
        KotlinBaseHighlightingBundle.message(declaration.toNameKey(), declaration.name ?: declaration.text)

    private fun KtNamedDeclaration.toNameKey(): String =
        when (this) {
            is KtPrimaryConstructor -> "safe.delete.primary.ctor.text.0"
            is KtSecondaryConstructor -> "safe.delete.secondary.ctor.text.0"
            is KtParameter -> "safe.delete.parameter.text.0"
            else -> "safe.delete.text.0"
        }

    override fun getText(): @IntentionName String {
       return name
    }

    override fun getFamilyName(): String = KotlinBaseHighlightingBundle.message("safe.delete.family")

    override fun startInWriteAction(): Boolean = false

    override fun invoke(
        project: Project,
        file: PsiFile,
        editor: Editor?,
        startElement: PsiElement,
        endElement: PsiElement
    ) {
        val element = startElement as? KtDeclaration ?: return

        SafeDeleteHandler.invoke(project, arrayOf(element), false)
    }
}