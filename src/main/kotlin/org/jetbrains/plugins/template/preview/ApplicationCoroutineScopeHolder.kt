package org.jetbrains.plugins.template.preview

import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope

@Service(Service.Level.APP)
internal class ApplicationCoroutineScopeHolder(val coroutineScope: CoroutineScope)