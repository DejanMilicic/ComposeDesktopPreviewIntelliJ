@file:OptIn(FlowPreview::class, ExperimentalJewelApi::class, InternalJewelApi::class)

package org.jetbrains.plugins.template.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.Color
import com.intellij.debugger.ui.HotSwapUIImpl
import com.intellij.ide.plugins.PluginManager
import com.intellij.java.library.JavaLibraryUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskManager
import com.intellij.task.impl.ProjectTaskManagerImpl
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.jewel.bridge.LocalComponent
import org.jetbrains.jewel.bridge.actionSystem.RootDataProviderModifier
import org.jetbrains.jewel.bridge.theme.SwingBridgeTheme
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.InternalJewelApi
import org.jetbrains.jewel.ui.component.Text
import java.awt.BorderLayout
import java.awt.Component
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.file.Files
import javax.swing.JPanel
import kotlin.coroutines.resume
import kotlin.io.path.Path

@ExperimentalJewelApi
class ComposePreviewToolWindowFactory : ToolWindowFactory {

    private val watcher = ComposableModificationWatcher

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentFactory = ContentFactory.getInstance()
        val wrapperPanel = JewelComposePanelWrapper()

        val composePanel = ComposePanel()

        composePanel.setContent(wrapperPanel) {
            Column(
                Modifier.background(Color.White)
            ) {

                Text(
                    "Preview will appear here\nPreview will appear here\nPreview will appear here\nPreview will appear here\nPreview will appear here\n",
                )
            }
        }

        wrapperPanel.add(composePanel, BorderLayout.CENTER)

        val toolWindowContent = contentFactory.createContent(wrapperPanel, "", false)
        toolWindow.contentManager.addContent(toolWindowContent)

        val coroutineScope = project.service<MyCoroutineScopeHolder>().coroutineScope
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            watcher.observeEditorContentChanges(toolWindow.disposable)
                .debounce(3000L)
                .distinctUntilChanged()
                .collect { (_, virtualFile) ->
                    try {
                        val compiledFun = compileCode(virtualFile, project) ?: return@collect

                        withContext(Dispatchers.EDT) {
                            composePanel.setContent {
                                SwingBridgeTheme {
                                    CompositionLocalProvider {
                                        ComponentDataProviderBridge(wrapperPanel, content = {
                                            compiledFun.invoke(null, currentComposer, currentCompositeKeyHash)
                                        })
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        thisLogger().error(e)
                    }
                }
        }
    }
}

private data class ModulePaths(val module: Module, val paths: List<String>)

private suspend fun compileCode(fileToCompile: VirtualFile, project: Project): Method? {
    val moduleData = readAction {
        val m = ModuleUtilCore.findModuleForFile(fileToCompile, project)
        m.takeIf { JavaLibraryUtil.hasLibraryClass(m, "androidx.compose.runtime.Composable") }
            ?.let {
                val paths = OrderEnumerator.orderEntries(it)
                    .recursively().withoutSdk().pathsList.pathList
                ModulePaths(it, paths)
            }
    } ?: return null

    return withContext(Dispatchers.EDT) {
        if (moduleData.module.isDisposed) return@withContext null
        if (!fileToCompile.isValid) return@withContext null

        val files = compileFiles(fileToCompile, project)
        if (files.isEmpty()) return@withContext null

        val diskPaths = moduleData.paths
            .mapNotNull { p -> Path(p).takeIf { Files.exists(it) }?.toUri()?.toURL() }
            .toTypedArray()

        // todo dispose previously created loaders on refresh
        val pluginByClass = PluginManager.getPluginByClass(ComposePreviewToolWindowFactory::class.java)
        val parent = pluginByClass!!.classLoader
        val loader = URLClassLoader("ComposePreview", diskPaths, parent)
        val javaClass = loader.loadClass("org.jetbrains.plugins.template.ui.ChatAppSampleKt")
        val function = javaClass.methods
            .firstOrNull { it.name == "ChatAppSample" && it.parameterCount == 2 }
            ?: return@withContext null

        function
    }
}

private suspend fun compileFiles(fileToCompile: VirtualFile, project: Project): List<VirtualFile> {
    val taskManager = ProjectTaskManager.getInstance(project) as ProjectTaskManagerImpl
    val task = readAction { taskManager.createModulesFilesTask(arrayOf(fileToCompile.parent)) }

    return suspendCancellableCoroutine { continuation ->
        try {
            taskManager.run(ProjectTaskContext(true).withUserData(HotSwapUIImpl.SKIP_HOT_SWAP_KEY, true), task)
                .onSuccess {
                    continuation.resume(listOf(fileToCompile))
                }
        } catch (e: Exception) {
            logger<ComposePreviewToolWindowFactory>().warn(e)
            continuation.resume(emptyList())
        }
    }
}

private fun ComposePanel.setContent(wrapperPanel: JewelComposePanelWrapper, content: @Composable () -> Unit) {
    setContent {
        SwingBridgeTheme {
            CompositionLocalProvider(LocalComponent provides this@setContent) {
                ComponentDataProviderBridge(wrapperPanel, content = content)
            }
        }
    }
}

@Suppress("FunctionName")
@Composable
fun ComponentDataProviderBridge(
    component: JewelComposePanelWrapper,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val rootDataProviderModifier = remember { RootDataProviderModifier() }

    Box(modifier = Modifier.then(rootDataProviderModifier).then(modifier)) { content() }

    DisposableEffect(component) {
        component.targetProvider = rootDataProviderModifier

        onDispose {
            if (component.targetProvider == rootDataProviderModifier) {
                component.targetProvider = null
            }
        }
    }
}

class JewelComposePanelWrapper : JPanel(), UiDataProvider {
    internal var targetProvider: UiDataProvider? = null

    val composePanel: ComposePanel
        get() =
            components.singleOrNull() as? ComposePanel
                ?: error("JewelComposePanelWrapper was not initialized with a ComposePanel")

    override fun addImpl(comp: Component, constraints: Any?, index: Int) {
        require(components.isEmpty()) {
            "JewelComposePanelWrapper can only contain a single ComposePanel, attempt to add another component"
        }

        require(comp is ComposePanel) {
            "JewelComposePanelWrapper can only contain ComposePanel, attempt to add ${comp::class.java.name}"
        }

        super.addImpl(comp, constraints, index)
    }

    override fun uiDataSnapshot(sink: DataSink) {
        targetProvider?.uiDataSnapshot(sink)
    }
}

object ComposableModificationWatcher {
    fun observeEditorContentChanges(disposable: Disposable): Flow<Pair<String, VirtualFile>> {
        return callbackFlow {
            val listener = object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val document = event.document
                    val vf = FileDocumentManager.getInstance().getFile(document) ?: return

                    trySend(document.text to vf!!)
                }
            }

            EditorFactory.getInstance().eventMulticaster.addDocumentListener(listener, disposable)

            val editorOpenListener = object : EditorFactoryListener {
                override fun editorCreated(event: EditorFactoryEvent) {
                    super.editorCreated(event)

                    val document = event.editor.document
                    val vf = FileDocumentManager.getInstance().getFile(document) ?: return
                    trySend(document.text to vf)
                }
            }

            EditorFactory.getInstance().addEditorFactoryListener(editorOpenListener, disposable)
            awaitClose {
                EditorFactory.getInstance().eventMulticaster.removeDocumentListener(listener)
            }
        }
    }
}