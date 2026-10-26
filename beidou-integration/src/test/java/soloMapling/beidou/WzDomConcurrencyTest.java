package soloMapling.beidou;

import org.gms.provider.Data;
import org.gms.provider.wz.XMLDomMapleData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Node;

import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class WzDomConcurrencyTest {
    @TempDir
    Path directory;

    @Test
    void wrappersFromTheSameDocumentUseTheDocumentMonitor() throws Exception {
        XMLDomMapleData root = document();
        Data first = root.getChildByPath("items");
        Data second = root.getChildByPath("items");
        assertNotSame(first, second);
        Field field = XMLDomMapleData.class.getDeclaredField("node");
        field.setAccessible(true);
        Node node = (Node) field.get(root);
        Object document = node.getOwnerDocument();
        CountDownLatch entered = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> one;
            Future<String> two;
            synchronized (document) {
                one = executor.submit(() -> { entered.countDown(); return first.getName(); });
                two = executor.submit(() -> { entered.countDown(); return second.getName(); });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> one.get(100, TimeUnit.MILLISECONDS));
                assertThrows(TimeoutException.class, () -> two.get(100, TimeUnit.MILLISECONDS));
            }
            assertEquals("items", one.get(5, TimeUnit.SECONDS));
            assertEquals("items", two.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentNativeAndSoloStyleReadsKeepEveryValueIntact() throws Exception {
        XMLDomMapleData root = document();
        List<Data> wrappers = new ArrayList<>();
        for (int thread = 0; thread < 32; thread++) wrappers.add(root.getChildByPath("items"));
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> work = new ArrayList<>();
            for (int thread = 0; thread < wrappers.size(); thread++) {
                int offset = thread;
                Data wrapper = wrappers.get(thread);
                work.add(executor.submit(() -> {
                    for (int iteration = 0; iteration < 500; iteration++) {
                        int id = (iteration + offset) % 64;
                        Data item = wrapper.getChildByPath("item" + id);
                        assertEquals("name-" + id, item.getChildByPath("name").getData());
                        assertEquals(1, item.getChildren().size());
                        assertEquals("item" + id, item.getName());
                    }
                }));
            }
            for (Future<?> future : work) future.get(15, TimeUnit.SECONDS);
        }
    }

    private XMLDomMapleData document() throws Exception {
        StringBuilder xml = new StringBuilder("<imgdir name=\"root\"><imgdir name=\"items\">");
        for (int id = 0; id < 64; id++) {
            xml.append("<imgdir name=\"item").append(id).append("\"><string name=\"name\" value=\"name-")
                    .append(id).append("\"/></imgdir>");
        }
        xml.append("</imgdir></imgdir>");
        Path file = directory.resolve("test.img.xml");
        Files.writeString(file, xml);
        try (FileInputStream input = new FileInputStream(file.toFile())) {
            return new XMLDomMapleData(input, directory);
        }
    }
}
