package soloMapling.beidou;

import org.gms.controller.AccountController;
import org.gms.controller.ServerController;
import org.junit.jupiter.api.Test;
import org.springframework.core.DefaultParameterNameDiscoverer;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 参数发现只读取 class 元数据，不实例化控制器或连接数据库。 */
class ControllerParameterMetadataTest {
    @Test
    void implicitChannelQueryParameterIsVisibleToSpring() throws NoSuchMethodException {
        Method method = ServerController.class.getDeclaredMethod("channelList", int.class);
        Parameter parameter = method.getParameters()[0];

        assertTrue(parameter.isNamePresent(), "channelList 必须保留 MethodParameters");
        assertEquals("worldId", parameter.getName());
        assertArrayEquals(new String[]{"worldId"},
                new DefaultParameterNameDiscoverer().getParameterNames(method));
    }

    @Test
    void accountQueryParameterNamesSurviveNativeCompilationAndWeaving() throws NoSuchMethodException {
        Method method = AccountController.class.getDeclaredMethod("getAccountList",
                Integer.class, Integer.class, Integer.class, String.class,
                String.class, String.class, String.class, String.class);
        String[] expected = {"page", "size", "id", "name", "lastLoginStart",
                "lastLoginEnd", "createdAtStart", "createdAtEnd"};

        assertTrue(Arrays.stream(method.getParameters()).allMatch(Parameter::isNamePresent));
        assertArrayEquals(expected, new DefaultParameterNameDiscoverer().getParameterNames(method));
    }

    @Test
    void soloSourceParametersAreAlsoRetainedByAspectJ() throws NoSuchMethodException {
        Method method = HostCompatibility.class.getDeclaredMethod("isGeneralChatKind", String.class);

        assertTrue(method.getParameters()[0].isNamePresent());
        assertArrayEquals(new String[]{"kind"},
                new DefaultParameterNameDiscoverer().getParameterNames(method));
    }
}
