// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.vcs.git.shared.widget.popup

import com.intellij.dvcs.branch.DvcsBranchesDivergedBanner
import com.intellij.dvcs.ui.DvcsBundle
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.platform.project.projectId
import com.intellij.ui.popup.WizardPopup
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.vcs.git.shared.ref.GitReferenceName
import com.intellij.vcs.git.shared.repo.GitRepositoriesHolder
import com.intellij.vcs.git.shared.repo.GitRepositoryModel
import com.intellij.vcs.git.shared.rpc.GitRepositoryApi
import com.intellij.vcs.git.shared.widget.actions.GitBranchesWidgetActions
import com.intellij.vcs.git.shared.widget.tree.GitBranchesTreeFilters
import com.intellij.vcs.git.shared.widget.tree.GitBranchesTreeModel.RefUnderRepository
import com.intellij.vcs.git.shared.widget.tree.GitBranchesTreeRenderer
import git4idea.GitReference
import git4idea.i18n.GitBundle
import org.intellij.lang.annotations.Language
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.KeyStroke

internal class GitBranchesTreePopup private constructor(
  project: Project,
  step: GitBranchesTreePopupStep,
  parent: JBPopup? = null,
  parentValue: Any? = null,
) : GitBranchesTreePopupBase<GitBranchesTreePopupStep>(project, step, parent, parentValue, DIMENSION_SERVICE_KEY) {
  init {
    installGeneralShortcutActions()
    if (!isNestedPopup()) {
      warnThatBranchesDivergedIfNeeded()
    }
  }

  override fun getSearchFiledEmptyText(): String {
    val isActionFilterSelected = GitBranchesTreeFilters.byActions(project)
    val isRepositoryFilterSelected = GitBranchesTreeFilters.byRepositoryName(project)
    val separatorKind = if (isActionFilterSelected && isRepositoryFilterSelected) 2 else 1
    val byActions =
      if (isActionFilterSelected) GitBundle.message("git.branches.popup.search.field.actions.empty.text", separatorKind) else ""
    val byRepository =
      if (isRepositoryFilterSelected) GitBundle.message("git.branches.popup.search.field.repositories.empty.text", separatorKind) else ""

    return GitBundle.message("git.branches.popup.search.field.empty.text", byActions, byRepository)
  }

  private fun warnThatBranchesDivergedIfNeeded() {
    if (treeStep.isBranchesDiverged()) {
      setWarning(DvcsBundle.message("branch.popup.warning.branches.have.diverged"))
    }
  }

  override fun getTreeEmptyText(searchPattern: String?): String {
    val filtersActive =
      GitBranchesTreeFilters.byActions(project) || GitBranchesTreeFilters.byRepositoryName(project)

    return if (filtersActive) GitBundle.message("git.branches.popup.tree.no.nodes", searchPattern)
    else GitBundle.message("git.branches.popup.tree.no.branches", searchPattern)
  }

  override fun handleIconClick(userObject: Any?): Boolean {
    toggleFavorite(userObject)
    return true
  }

  private fun toggleFavorite(userObject: Any?) {
    val refUnderRepository = userObject as? RefUnderRepository
    val reference = userObject as? GitReference ?: refUnderRepository?.ref ?: return
    val repositories = refUnderRepository?.repository?.let(::listOf) ?: treeStep.affectedRepositories

    val makeFavorite = repositories.any { !it.favoriteRefs.contains(reference) }

    GitRepositoryApi.launchRequest(project) {
      toggleFavorite(project.projectId(),
                     repositories.map { it.repositoryId },
                     GitReferenceName(reference.fullName),
                     favorite = makeFavorite)
    }
  }

  private fun installGeneralShortcutActions() {
    registerAction("toggle_favorite", KeyStroke.getKeyStroke("SPACE"), object : AbstractAction() {
      override fun actionPerformed(e: ActionEvent?) {
        tree.lastSelectedPathComponent?.let(TreeUtil::getUserObject).run(::toggleFavorite)
      }
    })
  }

  override fun getHeaderToolbar(): ActionToolbar {
    val settingsGroup = am.getAction(GitBranchesTreePopupActions.HEADER_SETTINGS_GROUP)
    val fetchAction = am.getAction(GitBranchesTreePopupActions.FETCH)
    val toolbarGroup = DefaultActionGroup(fetchAction, settingsGroup)
    return am.createActionToolbar(GitBranchesWidgetActions.MAIN_POPUP_ACTION_PLACE, toolbarGroup, true)
      .apply {
        targetComponent = content
        setReservePlaceAutoPopupIcon(false)
        component.isOpaque = false
      }
  }

  override fun createNextStepPopup(nextStep: PopupStep<*>?, parentValue: Any): WizardPopup =
    if (nextStep is GitBranchesTreePopupStep) GitBranchesTreePopup(project, nextStep, this, parentValue)
    else super.createPopup(this, nextStep, parentValue)

  override fun createRenderer(): GitBranchesTreeRenderer {
    return GitBranchesTreePopupRenderer(treeStep)
  }

  override fun createWarning(text: String): JComponent {
    return DvcsBranchesDivergedBanner.create("reference.VersionControl.Git.SynchronousBranchControl", text)
  }

  override fun getShortcutActionPlace(): String = if (isNestedPopup()) GitBranchesWidgetActions.NESTED_POPUP_ACTION_PLACE else GitBranchesWidgetActions.MAIN_POPUP_ACTION_PLACE

  companion object {
    private const val DIMENSION_SERVICE_KEY = "Git.Branch.Popup"

    fun create(project: Project, preferredSelection: GitRepositoryModel?): GitBranchesTreePopup {
      val repositories = GitRepositoriesHolder.getInstance(project).getAll().sorted()
      return GitBranchesTreePopup(project, GitBranchesTreePopupStep.create(project, preferredSelection, repositories)).also {
        it.setIsMovable(true)
      }
    }
  }
}

private object GitBranchesTreePopupActions {
  @Language("devkit-action-id")
  const val HEADER_SETTINGS_GROUP = "Git.Branches.Popup.Settings"

  @Language("devkit-action-id")
  const val FETCH = "Git.Branches.Popup.Fetch"
}
