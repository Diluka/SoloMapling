package soloMapling.beidou;

import org.gms.manager.ServerManager;
import org.gms.property.ServiceProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import org.mockito.Answers;

import static org.mockito.Mockito.mock;

/** Minimal in-memory beans for legacy static lookups; no Spring boot or datasource. */
public final class CharacterTestContext {
    private static boolean installed;

    private CharacterTestContext() { }

    public static synchronized void install() {
        if (installed) return;
        ConcurrentHashMap<Class<?>, Object> beans = new ConcurrentHashMap<>();
        ServiceProperty service = new ServiceProperty();
        service.setLanguage("en-US");
        beans.put(ServiceProperty.class, service);
        StaticMessageSource messages = new StaticMessageSource();
        messages.setUseCodeAsDefaultMessage(true);
        beans.put(MessageSource.class, messages);
        ApplicationContext context = mock(ApplicationContext.class, invocation -> {
            if (invocation.getMethod().getName().equals("getBean")) {
                Object[] args = invocation.getArguments();
                Class<?> type = (Class<?>) args[args.length - 1];
                return beans.computeIfAbsent(type, key -> mock(key));
            }
            return Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        try {
            Field field = ServerManager.class.getDeclaredField("applicationContext");
            field.setAccessible(true);
            field.set(null, context);
            installed = true;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
