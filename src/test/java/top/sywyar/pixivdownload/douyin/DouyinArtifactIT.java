package top.sywyar.pixivdownload.douyin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pf4j.Plugin;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class DouyinArtifactIT {

    @Test
    @DisplayName("最终包保持版本、完整资源和薄插件边界，入口从包内加载")
    void packagedPluginPreservesIdentityAndResources() throws Exception {
        Path artifact = Path.of(System.getProperty("plugin.jar")).toAbsolutePath();
        Properties descriptor = new Properties();
        try (JarFile jar = new JarFile(artifact.toFile())) {
            try (var input = jar.getInputStream(jar.getJarEntry("plugin.properties"))) {
                descriptor.load(input);
            }
            assertEquals(System.getProperty("plugin.version"), descriptor.getProperty("plugin.version"));
            assertEquals("douyin", descriptor.getProperty("plugin.id"));
            assertEquals("host-process-full-trust", descriptor.getProperty("pixiv.execution-mode"));
            assertEquals("process-restart", descriptor.getProperty("pixiv.lifecycle-policy"));
            assertTrue(jar.stream().noneMatch(entry -> {
                String name = entry.getName();
                return name.startsWith("BOOT-INF/") || name.startsWith("lib/") || name.endsWith(".jar")
                        || name.startsWith("sdk/") || name.startsWith("tools/") || name.startsWith("fixtures/")
                        || (name.endsWith(".class") && !name.startsWith("top/sywyar/pixivdownload/douyin/"));
            }));
            Path resources = Path.of("src/main/resources");
            try (var files = Files.walk(resources)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String name = resources.relativize(file).toString().replace('\\', '/');
                    var entry = jar.getJarEntry(name);
                    assertNotNull(entry, name);
                    try (var input = jar.getInputStream(entry)) {
                        assertArrayEquals(Files.readAllBytes(file), input.readAllBytes(), name);
                    }
                }
            }
        }
        try (var loader = new URLClassLoader(new URL[]{artifact.toUri().toURL()}, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith("top.sywyar.pixivdownload.douyin.")) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
        }) {
            Class<?> type = loader.loadClass(descriptor.getProperty("plugin.class"));
            Object instance = type.getDeclaredConstructor().newInstance();
            assertInstanceOf(Plugin.class, instance);
            PixivPluginProvider provider = assertInstanceOf(PixivPluginProvider.class, instance);
            assertEquals(descriptor.getProperty("plugin.id"), provider.featurePlugin().id());
            assertFalse(provider.configurationClasses().isEmpty());
            assertEquals(loader, provider.configurationClasses().get(0).getClassLoader());
            assertEquals(artifact.toUri(), type.getProtectionDomain().getCodeSource().getLocation().toURI());
        }
    }
}
