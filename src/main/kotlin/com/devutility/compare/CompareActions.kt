package com.devutility.compare

import com.devutility.props.PropertyFileScanner
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.vfs.VirtualFile

/** Tools | Dev Utility | Compare Property Files… */
class OpenPropertyCompareAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        PropertyCompareToolWindow.show(project)
    }
}

/**
 * Project view context menu. Selecting two files loads both sides; selecting one loads the
 * left side and leaves the right for you to pick.
 */
class CompareSelectedPropertyFilesAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val files = configFiles(e)
        e.presentation.isEnabledAndVisible = e.project != null && files.isNotEmpty() && files.size <= 2
        e.presentation.text = when (files.size) {
            2 -> "Compare '${files[0].name}' with '${files[1].name}'"
            else -> "Compare Property Files"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = configFiles(e)
        PropertyCompareToolWindow.show(project, files.getOrNull(0), files.getOrNull(1))
    }

    private fun configFiles(e: AnActionEvent): List<VirtualFile> =
        (e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: emptyArray())
            .filter { PropertyFileScanner.isConfigFile(it) }
}
