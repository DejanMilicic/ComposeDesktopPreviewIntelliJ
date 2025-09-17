package org.jetbrains.plugins.template.preview;

import androidx.compose.ui.awt.ComposePanel;
import kotlin.Unit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class JvmReflectBridge {
    public static void setPreviewContent(ComposePanel panel, Method method) {
        panel.setContent((composer, integer) -> {
            try {
                method.invoke(null, composer, (int)integer);
            } catch (IllegalAccessException | InvocationTargetException | IllegalArgumentException e) {
                throw new RuntimeException(e);
            }
            return Unit.INSTANCE;
        });
    }
}
