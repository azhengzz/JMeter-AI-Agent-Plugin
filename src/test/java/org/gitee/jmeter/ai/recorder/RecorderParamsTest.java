package org.gitee.jmeter.ai.recorder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 预填优先级（上次 > 属性 > 空/默认）、记忆持久化、域名/类型解析。 */
class RecorderParamsTest {

    private static final Set<String> DEF_TYPES = Set.of("xhr", "fetch");
    private static final int DEF_MAX_BODY = 65536;

    @TempDir
    Path dir;

    private RecorderParams.Start prefill(Path file, String propUrl, String propDomains) {
        return RecorderParams.prefill(file, propUrl, propDomains, DEF_TYPES, DEF_MAX_BODY);
    }

    @Test
    void noHistoryNoPropsGivesEmpty() {
        RecorderParams.Start s = prefill(dir.resolve("none.properties"), null, null);
        assertEquals("", s.url());
        assertTrue(s.domains().isEmpty());
        // 类型/截断无历史时回退属性默认
        assertEquals(DEF_TYPES, s.resourceTypes());
        assertEquals(DEF_MAX_BODY, s.maxBodyBytes());
    }

    @Test
    void propsPrefillWhenNoHistory() {
        RecorderParams.Start s = prefill(dir.resolve("none.properties"),
                "https://example.com/ops", "example.com, api.example.com");
        assertEquals("https://example.com/ops", s.url());
        assertEquals(Set.of("example.com", "api.example.com"), s.domains());
    }

    @Test
    void lastUsedWinsOverProps() {
        Path last = dir.resolve("last-params.properties");
        RecorderParams.saveLast(last, new RecorderParams.Start("https://last.example/home",
                Set.of("last.example"), Set.of("xhr"), 1024));
        RecorderParams.Start s = RecorderParams.prefill(last, "https://prop.example", "prop.example",
                DEF_TYPES, DEF_MAX_BODY);
        assertEquals("https://last.example/home", s.url());
        assertEquals(Set.of("last.example"), s.domains());
        assertEquals(Set.of("xhr"), s.resourceTypes());
        assertEquals(1024, s.maxBodyBytes());
    }

    @Test
    void saveThenPrefillRoundtrip() {
        Path last = dir.resolve("last-params.properties");
        RecorderParams.saveLast(last, new RecorderParams.Start("https://example.com/x",
                Set.of("example.com", "cdn.example.com"), Set.of("xhr", "fetch", "document"), 0));
        RecorderParams.Start s = RecorderParams.prefill(last, null, null, DEF_TYPES, DEF_MAX_BODY);
        assertEquals("https://example.com/x", s.url());
        assertEquals(Set.of("example.com", "cdn.example.com"), s.domains());
        assertEquals(Set.of("xhr", "fetch", "document"), s.resourceTypes());
        assertEquals(0, s.maxBodyBytes(), "0 = 不截断，记忆值须原样回读");
    }

    @Test
    void legacyLastFileWithoutTypesFallsBackToProps() {
        // 旧版记忆文件只存 url/domains：类型/截断回退属性默认，url/domains 仍读上次
        Path last = dir.resolve("legacy.properties");
        RecorderParams.saveLast(last, new RecorderParams.Start("https://old.example", Set.of("old.example"),
                Set.of(), null));
        RecorderParams.Start s = RecorderParams.prefill(last, "https://prop.example", "prop.example",
                DEF_TYPES, DEF_MAX_BODY);
        assertEquals("https://old.example", s.url());
        assertEquals(DEF_TYPES, s.resourceTypes());
        assertEquals(DEF_MAX_BODY, s.maxBodyBytes());
    }

    @Test
    void corruptMaxBodyInLastFileFallsBackToProps() {
        Path last = dir.resolve("corrupt.properties");
        try {
            java.util.Properties p = new java.util.Properties();
            p.setProperty("url", "u");
            p.setProperty("domains", "");
            p.setProperty("resource.types", "xhr");
            p.setProperty("max.body.bytes", "not-a-number");
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(last)) {
                p.store(out, "corrupt");
            }
        } catch (Exception e) {
            fail(e);
        }
        RecorderParams.Start s = RecorderParams.prefill(last, null, null, DEF_TYPES, DEF_MAX_BODY);
        assertEquals(Set.of("xhr"), s.resourceTypes());
        assertEquals(DEF_MAX_BODY, s.maxBodyBytes());
    }




    @Test
    void parseDomainsTrimsAndSkipsBlanks() {
        assertEquals(Set.of("a.com", "b.com"), RecorderParams.parseDomains(" a.com , b.com ,, "));
        assertTrue(RecorderParams.parseDomains(null).isEmpty());
        assertTrue(RecorderParams.parseDomains("   ").isEmpty());
    }

    @Test
    void parseTypesLowercasesForWhitelistMatch() {
        assertEquals(Set.of("xhr", "fetch"), RecorderParams.parseTypes(" XHR , Fetch "));
        assertTrue(RecorderParams.parseTypes(null).isEmpty());
        assertTrue(RecorderParams.parseTypes(" ,, ").isEmpty());
    }

    @Test
    void saveLastSurvivesUnwritableDir() {
        // best-effort：目录不存在也应能建；指向一个文件路径则静默失败不抛
        RecorderParams.saveLast(dir.resolve("is-a-file.properties"),
                new RecorderParams.Start("u", Set.of("d"), Set.of("xhr"), 1));
        // 不抛即过
    }
}
