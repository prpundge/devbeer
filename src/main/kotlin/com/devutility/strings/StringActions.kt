package com.devutility.strings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import javax.swing.JList

private const val NOTIFICATION_GROUP = "DevBeer"

/** Tools | DevBeer | String Toolbox */
class OpenStringToolboxAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        StringToolboxToolWindow.show(e.project ?: return)
    }
}

/** Editor context menu — hand the selection to the toolbox without leaving the keyboard. */
class SendSelectionToToolboxAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor?.selectionModel?.hasSelection() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val text = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText ?: return
        StringToolboxToolWindow.show(project, text)
    }
}

/**
 * Editor context menu — pick a transformation from a searchable popup and rewrite the
 * selection in place, as a single undoable command.
 */
class TransformSelectionAction : AnAction(), DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible =
            editor != null && editor.selectionModel.hasSelection() && editor.document.isWritable
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selection = editor.selectionModel.selectedText ?: return
        val start = editor.selectionModel.selectionStart
        val end = editor.selectionModel.selectionEnd

        // Report-style transforms (statistics, word frequency) would destroy the code they
        // replace, so they are not offered here — the toolbox is the place for those.
        val choices = StringTransforms.ALL.filter { it.editorSafe }

        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(choices)
            .setTitle("Transform Selection")
            .setNamerForFiltering { "${it.category} ${it.title}" }
            .setRenderer(object : SimpleListCellRenderer<StringTransform>() {
                override fun customize(
                    list: JList<out StringTransform>,
                    value: StringTransform?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    text = value?.let { "${it.title}    ·    ${it.category}" } ?: ""
                    toolTipText = value?.description
                }
            })
            .setItemChosenCallback { transform -> applyTransform(project, editor, transform, selection, start, end) }
            .createPopup()
            .showInBestPositionFor(editor)
    }

    private fun applyTransform(
        project: Project?,
        editor: com.intellij.openapi.editor.Editor,
        transform: StringTransform,
        selection: String,
        start: Int,
        end: Int,
    ) {
        val result = try {
            transform.apply(selection)
        } catch (ex: Exception) {
            notifyFailure(project, transform, ex)
            return
        }

        WriteCommandAction.runWriteCommandAction(project, "Transform Selection: ${transform.title}", "DevBeer", {
            editor.document.replaceString(start, end, result)
        })
    }

    private fun notifyFailure(project: Project?, transform: StringTransform, ex: Exception) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(
                "${transform.title} could not be applied",
                ex.message ?: ex.javaClass.simpleName,
                NotificationType.WARNING,
            )
            .notify(project)
    }
}
