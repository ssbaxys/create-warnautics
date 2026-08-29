package com.cbc_more_content.util;

import com.cbc_more_content.CBCMoreContent;
import java.lang.reflect.InvocationTargetException;
import net.neoforged.fml.loading.FMLEnvironment;

/** Invokes optional static handlers while avoiding client class loading on dedicated servers. */
public final class ReflectiveDispatcher {
    private ReflectiveDispatcher() {}

    public static void invoke(String className, String methodName, Class<?>[] parameterTypes, Object... arguments) {
        if (parameterTypes == null || arguments == null || parameterTypes.length != arguments.length) {
            throw new IllegalArgumentException("Reflection parameter and argument counts must match");
        }
        if (className.contains(".client.") && !FMLEnvironment.dist.isClient()) {
            return;
        }
        try {
            Class.forName(className).getMethod(methodName, parameterTypes).invoke(null, arguments);
        } catch (ClassNotFoundException
                | NoSuchMethodException
                | IllegalAccessException
                | InvocationTargetException
                | SecurityException exception) {
            CBCMoreContent.LOGGER.debug(
                    "Optional handler {}.{} unavailable: {}", className, methodName, exception.toString());
        }
    }
}
