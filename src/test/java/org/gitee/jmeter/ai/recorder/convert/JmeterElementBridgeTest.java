package org.gitee.jmeter.ai.recorder.convert;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 任务 4.2/4.3：属性纯映射单测 +（JMETER_HOME 可用时）build→saveAsJmx→loadTree 读写回环。
 */
class JmeterElementBridgeTest {

    // ---- 纯映射（无 JMeter 依赖） ----

    @Test
    void threadGroupProps() {
        ElementDef def = ElementDef.of("threadgroup", "录制 x",
                Map.of("numThreads", 2, "rampUp", 3, "loops", 4));
        Map<String, Object> out = JmeterElementBridge.translate(def);
        assertEquals(2, out.get("ThreadGroup.num_threads"));
        assertEquals(3, out.get("ThreadGroup.ramp_time"));
        assertEquals(Map.of("LoopController.loops", 4), out.get("ThreadGroup.main_controller"));
    }

    @Test
    void cookieManagerProps() {
        ElementDef def = ElementDef.of("cookiemanager", "c", Map.of(
                "clearEachIteration", false,
                "cookies", List.of(Map.of("name", "sess", "value", "v",
                        "domain", "example.com", "path", "/", "secure", true))));
        Map<String, Object> out = JmeterElementBridge.translate(def);
        assertEquals(false, out.get("CookieManager.clearEachIteration"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cookies = (List<Map<String, Object>>) out.get("CookieManager.cookies");
        assertEquals(Map.of("Cookie.name", "sess", "Cookie.value", "v", "Cookie.domain", "example.com",
                "Cookie.path", "/", "Cookie.secure", true), cookies.get(0));
    }

    @Test
    void headerManagerProps() {
        ElementDef def = ElementDef.of("headermanager", "h",
                Map.of("headers", List.of(Map.of("name", "X-Biz", "value", "1"))));
        Map<String, Object> out = JmeterElementBridge.translate(def);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> headers = (List<Map<String, Object>>) out.get("HeaderManager.headers");
        assertEquals(Map.of("Header.name", "X-Biz", "Header.value", "1"), headers.get(0));
    }

    @Test
    void samplerWithUrlEncodedArguments() {
        ElementDef def = ElementDef.of("httpsampler", "POST /login#1", Map.of(
                "method", "POST", "path", "/login", "domain", "example.com", "port", 443,
                "protocol", "https",
                "arguments", List.of(Map.of("name", "user", "value", "tom"))));
        Map<String, Object> out = JmeterElementBridge.translate(def);
        assertEquals("POST", out.get("HTTPSampler.method"));
        assertEquals("/login", out.get("HTTPSampler.path"));
        assertEquals("example.com", out.get("HTTPSampler.domain"));
        assertEquals(443, out.get("HTTPSampler.port"));
        assertEquals(true, out.get("HTTPSampler.follow_redirects"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> args = (List<Map<String, Object>>) out.get("HTTPsampler.Arguments");
        assertEquals(Map.of("Argument.name", "user", "Argument.value", "tom"), args.get(0));
        assertNull(out.get("HTTPSampler.postBodyRaw"));
    }

    @Test
    void samplerWithRawPostData() {
        ElementDef def = ElementDef.of("httpsampler", "POST /api#1",
                Map.of("method", "POST", "path", "/api", "postData", "{\"x\":1}"));
        Map<String, Object> out = JmeterElementBridge.translate(def);
        assertEquals(true, out.get("HTTPSampler.postBodyRaw"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> args = (List<Map<String, Object>>) out.get("HTTPsampler.Arguments");
        assertEquals(1, args.size());
        assertEquals("", args.get(0).get("Argument.name"));
        assertEquals("{\"x\":1}", args.get(0).get("Argument.value"));
    }

    @Test
    void defaultsProps() {
        ElementDef def = ElementDef.of("httpdefaults", "d",
                Map.of("protocol", "https", "domain", "example.com", "port", 443));
        Map<String, Object> out = JmeterElementBridge.translate(def);
        assertEquals("https", out.get("HTTPSampler.protocol"));
        assertEquals("example.com", out.get("HTTPSampler.domain"));
        assertEquals(443, out.get("HTTPSampler.port"));
    }

    // ---- jmx 读写回环（需 JMETER_HOME 定位 saveservice.properties，同 SnapshotTreeCloneTest 前置） ----

    @Test
    void planNodeResolutionSkipsVirtualRoot() throws Exception {
        // 骨架落点必须是 TestPlan 节点（虚拟根的首子）而非虚拟根本身——
        // 挂 root 会与 TestPlan 平级，游离在计划外、不参与运行
        org.apache.jmeter.gui.tree.JMeterTreeModel model = new org.apache.jmeter.gui.tree.JMeterTreeModel();
        org.apache.jmeter.gui.tree.JMeterTreeNode planNode = JmeterElementBridge.planNodeOf(model);
        assertNotEquals(model.getRoot(), planNode, "落点不得是隐形虚拟根（与 TestPlan 平级）");
        assertEquals(model.getRoot(), planNode.getParent(), "落点是虚拟根的直接子（首层）");
        assertTrue(planNode.getTestElement() instanceof org.apache.jmeter.testelement.TestPlan,
                "落点是 TestPlan 节点，骨架挂进计划内");
    }

    @TempDir
    Path dir;

    @org.junit.jupiter.api.BeforeAll
    static void initJMeterHome() {
        String home = System.getenv("JMETER_HOME");
        if (home == null) {
            home = System.getProperty("jmeter.home");
        }
        assumeTrue(home != null && new File(home, "bin" + File.separator + "saveservice.properties").isFile(),
                "JMETER_HOME unavailable; jmx roundtrip skipped");
        org.apache.jmeter.util.JMeterUtils.setJMeterHome(home);
    }

    @Test
    void buildAndSaveJmxRoundtrip() throws Exception {
        // 源码树技能目录装载 registry/schema（不依赖已安装插件的 bin/jmeter-agent）。
        // 全量套件下 registry 可能已被其他测试以空目录占位（loaded=true 幂等跳过），强制 reload。
        Path skillsDir = Path.of("src/main/jmeter-agent/skills");
        org.gitee.jmeter.ai.utils.ElementRegistry.getInstance().reload(skillsDir);
        JmeterElementBridge bridge = JmeterElementBridge.create(skillsDir);

        ElementDef samplerJson = ElementDef.of("httpsampler", "POST /pay#1", Map.of(
                "method", "POST", "path", "/pay", "domain", "api.example.com", "port", 443,
                "protocol", "https", "postData", "{\"amount\":9}"));
        ElementDef samplerForm = ElementDef.of("httpsampler", "POST /login#2", Map.of(
                "method", "POST", "path", "/login",
                "arguments", List.of(Map.of("name", "user", "value", "tom"))));
        ElementDef tc = ElementDef.of("transactioncontroller", "录制 x")
                .child(ElementDef.of("cookiemanager", "录制 Cookie", Map.of(
                        "clearEachIteration", false,
                        "cookies", List.of(Map.of("name", "sess", "value", "v",
                                "domain", "example.com", "path", "/", "secure", false)))))
                .child(ElementDef.of("headermanager", "录制 Headers", Map.of(
                        "headers", List.of(Map.of("name", "X-Biz", "value", "1")))))
                .child(ElementDef.of("httpdefaults", "录制 默认值",
                        Map.of("protocol", "https", "domain", "api.example.com", "port", 443)))
                .child(samplerJson)
                .child(samplerForm);
        ElementDef tg = ElementDef.of("threadgroup", "录制 x",
                Map.of("numThreads", 1, "rampUp", 1, "loops", 1)).child(tc);

        Path jmx = dir.resolve("recording-test.jmx");
        bridge.saveAsJmx(List.of(tg), jmx);
        assertTrue(Files.isRegularFile(jmx));

        // 回读：TestPlan → TG → TC → [CM, HM, Defaults, 2 samplers]
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(org.apache.jmeter.save.SaveService.class.getClassLoader());
        org.apache.jorphan.collections.HashTree loaded;
        try {
            loaded = org.apache.jmeter.save.SaveService.loadTree(jmx.toFile());
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
        org.apache.jmeter.testelement.TestElement plan = (org.apache.jmeter.testelement.TestElement)
                loaded.getArray()[0];
        assertEquals("Recording Plan", plan.getName());
        org.apache.jmeter.testelement.TestPlan testPlan = (org.apache.jmeter.testelement.TestPlan) plan;
        assertInstanceOf(org.apache.jmeter.config.Arguments.class,
                testPlan.getUserDefinedVariablesAsProperty().getObjectValue());
        assertDoesNotThrow(() -> new org.apache.jmeter.control.gui.TestPlanGui().configure(testPlan));
        org.apache.jorphan.collections.HashTree tgTree = loaded.getTree(plan);
        org.apache.jmeter.threads.ThreadGroup tgEl = (org.apache.jmeter.threads.ThreadGroup)
                tgTree.getArray()[0];
        assertEquals("录制 x", tgEl.getName());
        assertNotNull(tgEl.getSamplerController(), "ThreadGroup must carry a LoopController");
        org.apache.jorphan.collections.HashTree tcTree = tgTree.getTree(tgEl);
        org.apache.jmeter.control.TransactionController tcEl =
                (org.apache.jmeter.control.TransactionController) tcTree.getArray()[0];
        assertEquals("录制 x", tcEl.getName());
        org.apache.jorphan.collections.HashTree inner = tcTree.getTree(tcEl);
        assertEquals(5, inner.getArray().length);

        // 顺序断言（HashTree 底层 HashMap 会打乱另存 jmx 的子节点顺序——曾致采样器
        // #序号命名对而位置乱；ListedHashTree 修复后按插入序序列化，此处钉住）
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Object o : inner.getArray()) {
            names.add(((org.apache.jmeter.testelement.TestElement) o).getName());
        }
        assertEquals(java.util.List.of("录制 Cookie", "录制 Headers", "录制 默认值", "POST /pay#1", "POST /login#2"),
                names, "另存 jmx 须按定义顺序序列化");

        // JSON body 采样器：postBodyRaw + 无名 Argument
        org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy pay = (org.apache.jmeter.protocol.http.sampler.HTTPSamplerProxy)
                java.util.Arrays.stream(inner.getArray())
                        .filter(o -> ((org.apache.jmeter.testelement.TestElement) o).getName().equals("POST /pay#1"))
                        .findFirst().orElseThrow();
        assertEquals("POST", pay.getMethod());
        assertEquals("api.example.com", pay.getDomain());
        assertEquals("/pay", pay.getPath());
        assertEquals("{\"amount\":9}", pay.getArguments().getArgument(0).getValue());
    }
}
