package gov.anzong.fim.completion.startup

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.registry.Registry
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ForceDisableBuiltInCompletionActivity : ProjectActivity {
    private val log = Logger.getInstance(ForceDisableBuiltInCompletionActivity::class.java)

    // 独立的防抖调度器，专为强行唤醒服务
    private val debounceExecutor = Executors.newSingleThreadScheduledExecutor()
    private var lastSchedule: ScheduledFuture<*>? = null

    override suspend fun execute(project: Project) {
        // 1. 恢复官方底层总闸（保证底层的 Tab 键总监听器不被注销）
        try {
            val inlineRegistry = Registry.get("inline.completion.enabled")
            if (!inlineRegistry.asBoolean()) inlineRegistry.setValue(true)
            PropertiesComponent.getInstance().setValue("ml.completion.full.line.enabled", true)
        } catch (e: Exception) {}

        // 2. 突破 IDEA 封锁：注入全局键盘黑客监听器
        val eventMulticaster = EditorFactory.getInstance().eventMulticaster
        eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val newLength = event.newLength
                val oldLength = event.oldLength

                // 核心过滤：
                // 如果是正常的单字符打字 (newLength == 1)，IDEA 自己会触发，我们绝不插手。
                // 只有当【退格删除(newLength==0)】或【Tab采纳/粘贴(newLength>1)】时，我们才强制干预！
                if (newLength == 1 && oldLength == 0) return

                lastSchedule?.cancel(false)
                lastSchedule = debounceExecutor.schedule({
                    ApplicationManager.getApplication().invokeLater {
                        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@invokeLater
                        // 确保当前变更的文档就是用户正在看的那一个
                        if (editor.document != event.document) return@invokeLater

                        val actionManager = ActionManager.getInstance()
                        // 尝试提取 2024.x 版本用于手动触发 Inline Completion 的官方底层 Action ID
                        val actionIds = listOf("CallInlineCompletionAction", "EditorShowInlineCompletion")
                        val triggerAction = actionIds.firstNotNullOfOrNull { actionManager.getAction(it) }

                        if (triggerAction != null) {
                            val dataContext = DataContext { dataId ->
                                when {
                                    CommonDataKeys.EDITOR.`is`(dataId) -> editor
                                    CommonDataKeys.PROJECT.`is`(dataId) -> project
                                    else -> null
                                }
                            }
                            // 组装伪造的事件，直接强制触发 IDEA 补全
                            val actionEvent = AnActionEvent.createFromDataContext(ActionPlaces.UNKNOWN, Presentation(), dataContext)
                            ActionUtil.performActionDumbAwareWithCallbacks(triggerAction, actionEvent)
                        }
                    }
                }, 400, TimeUnit.MILLISECONDS) // 延迟 400ms，充当连续按退格键和连续 Tab 的防抖时间
            }
        }, project)

        log.info("AnzongFIM: Global Hacker Listener injected successfully.")
    }
}