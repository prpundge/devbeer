package com.devutility.folders

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory

class FolderCompareToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = FolderComparePanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

object FolderCompareToolWindow {

    const val ID = "Folder Compare"

    fun show(project: Project, left: VirtualFile? = null, right: VirtualFile? = null) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
        toolWindow.activate {
            val panel = toolWindow.contentManager.contents
                .firstNotNullOfOrNull { it.component as? FolderComparePanel }
            if (left != null || right != null) panel?.load(left, right)
        }
    }
}

/** Tools | DevBeer | Compare Folders… */
class OpenFolderCompareAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        FolderCompareToolWindow.show(e.project ?: return)
    }
}

/** Project view: select two directories and compare them. */
class CompareSelectedFoldersAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val dirs = directories(e)
        e.presentation.isEnabledAndVisible = e.project != null && dirs.isNotEmpty() && dirs.size <= 2
        e.presentation.text = when (dirs.size) {
            2 -> "Compare Folder '${dirs[0].name}' with '${dirs[1].name}'"
            else -> "Compare Folders"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dirs = directories(e)
        FolderCompareToolWindow.show(project, dirs.getOrNull(0), dirs.getOrNull(1))
    }

    private fun directories(e: AnActionEvent): List<VirtualFile> =
        (e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: emptyArray()).filter { it.isValid && it.isDirectory }
}
