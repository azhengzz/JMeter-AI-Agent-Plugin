package org.gitee.jmeter.ai.recorder.convert;

import org.apache.jmeter.config.Arguments;
import org.apache.jmeter.control.LoopController;
import org.apache.jmeter.control.gui.LoopControlPanel;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.TestElementProperty;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.gitee.jmeter.ai.agent.tools.jmeter.property.SchemaBasedPropertyHandler;
import org.gitee.jmeter.ai.agent.validation.ComponentSchema;
import org.gitee.jmeter.ai.agent.validation.ComponentSchemaLoader;
import org.gitee.jmeter.ai.utils.ElementRegistry;
import org.gitee.jmeter.ai.utils.JMeterElementManager;
import org.gitee.jmeter.ai.utils.WorkspacePaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ElementDef → JMeter 落地桥：元素构建（registry + schema 属性管线）、
 * EDT 插入当前测试计划、TestPlan 包裹另存 .jmx。
 *
 * <p>复用既有创建管线（{@link JMeterElementManager} + {@link SchemaBasedPropertyHandler}），
 * 属性映射 {@link #translate} 为纯函数（无 JMeter 依赖）可全量单测；
 * ThreadGroup 需初始化 LoopController、HTTP 系元素需先置空 HTTPsampler.Arguments
 * （均为既有 create 路径的前置要求）。
 */
public final class JmeterElementBridge {
    private static final Logger log = LoggerFactory.getLogger(JmeterElementBridge.class);

    private final ComponentSchemaLoader schemaLoader;
    private final SchemaBasedPropertyHandler propertyHandler = new SchemaBasedPropertyHandler();

    private JmeterElementBridge(ComponentSchemaLoader schemaLoader) {
        this.schemaLoader = schemaLoader;
    }

    /** 生产构造：registry/schema 从内置技能目录装载（幂等）。 */
    public static JmeterElementBridge create() {
        String home = JMeterUtils.getJMeterHome();
        if (home == null) {
            log.warn("JMeter home unavailable; recording skeleton import runs without schema validation");
            return new JmeterElementBridge(null);
        }
        return create(WorkspacePaths.builtinSkillsDir());
    }

    /** 测试构造：显式指定技能目录（源码树）。 */
    public static JmeterElementBridge create(Path skillsDir) {
        ElementRegistry.getInstance().loadFromSkillsDir(skillsDir);
        return new JmeterElementBridge(new ComponentSchemaLoader(skillsDir));
    }

    // ---- 纯属性映射 ----

    @SuppressWarnings("unchecked")
    static Map<String, Object> translate(ElementDef def) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (def.elementType()) {
            case "threadgroup" -> {
                out.put("ThreadGroup.num_threads", intOf(def.props().get("numThreads"), 1));
                out.put("ThreadGroup.ramp_time", intOf(def.props().get("rampUp"), 1));
                out.put("ThreadGroup.main_controller", Map.of(
                        "LoopController.loops", intOf(def.props().get("loops"), 1)));
            }
            case "cookiemanager" -> {
                out.put("CookieManager.clearEachIteration",
                        Boolean.TRUE.equals(def.props().get("clearEachIteration")));
                List<Map<String, Object>> cookies = new ArrayList<>();
                for (Object c : listOf(def.props().get("cookies"))) {
                    Map<String, Object> src = (Map<String, Object>) c;
                    Map<String, Object> cookie = new LinkedHashMap<>();
                    cookie.put("Cookie.name", str(src.get("name")));
                    cookie.put("Cookie.value", str(src.get("value")));
                    cookie.put("Cookie.domain", str(src.get("domain")));
                    cookie.put("Cookie.path", str(src.get("path"), "/"));
                    cookie.put("Cookie.secure", Boolean.TRUE.equals(src.get("secure")));
                    cookies.add(cookie);
                }
                out.put("CookieManager.cookies", cookies);
            }
            case "headermanager" -> {
                List<Map<String, Object>> headers = new ArrayList<>();
                for (Object h : listOf(def.props().get("headers"))) {
                    Map<String, Object> src = (Map<String, Object>) h;
                    headers.add(Map.of(
                            "Header.name", str(src.get("name")),
                            "Header.value", str(src.get("value"))));
                }
                out.put("HeaderManager.headers", headers);
            }
            case "httpdefaults" -> {
                putHttpTarget(out, def);
            }
            case "httpsampler" -> {
                putHttpTarget(out, def);
                out.put("HTTPSampler.method", str(def.props().getOrDefault("method", "GET")));
                out.put("HTTPSampler.path", str(def.props().get("path"), "/"));
                out.put("HTTPSampler.follow_redirects",
                        !Boolean.FALSE.equals(def.props().get("followRedirects")));
                out.put("HTTPSampler.use_keepalive",
                        !Boolean.FALSE.equals(def.props().get("useKeepalive")));
                Object args = def.props().get("arguments");
                Object postData = def.props().get("postData");
                if (args instanceof List<?> list) {
                    List<Map<String, Object>> arguments = new ArrayList<>();
                    for (Object a : list) {
                        Map<String, Object> src = (Map<String, Object>) a;
                        arguments.add(Map.of(
                                "Argument.name", str(src.get("name"), ""),
                                "Argument.value", str(src.get("value"), "")));
                    }
                    out.put("HTTPsampler.Arguments", arguments);
                } else if (postData != null) {
                    // raw body：postBodyRaw + 单个无名 Argument（JMeter 5.x 的 .jmx 形态）
                    out.put("HTTPSampler.postBodyRaw", true);
                    out.put("HTTPsampler.Arguments", List.of(Map.of(
                            "Argument.name", "",
                            "Argument.value", String.valueOf(postData))));
                }
            }
            default -> out.putAll(def.props());
        }
        return out;
    }

    private static void putHttpTarget(Map<String, Object> out, ElementDef def) {
        if (def.props().containsKey("protocol")) {
            out.put("HTTPSampler.protocol", def.props().get("protocol"));
        }
        if (def.props().containsKey("domain")) {
            out.put("HTTPSampler.domain", def.props().get("domain"));
        }
        if (def.props().containsKey("port")) {
            out.put("HTTPSampler.port", def.props().get("port"));
        }
    }

    // ---- 元素构建 / 插入 / 另存 ----

    /** 构建 TestElement（走 registry + schema 属性管线，守卫违规抛可读异常）。 */
    public TestElement build(ElementDef def) throws IOException {
        String type = JMeterElementManager.normalizeElementType(def.elementType());
        TestElement element = JMeterElementManager.createElement(type, def.name());
        if (element == null) {
            throw new IOException("元素创建失败：" + type + "（registry 未收录或类不可用）");
        }
        if ("threadgroup".equals(type)) {
            // ThreadGroup 必须有 LoopController 才可运行（addElement 同款前置）
            LoopController loop = new LoopController();
            loop.setLoops(1);
            loop.setFirst(true);
            loop.setProperty(TestElement.TEST_CLASS, LoopController.class.getName());
            loop.setProperty(TestElement.GUI_CLASS, LoopControlPanel.class.getName());
            ((org.apache.jmeter.threads.ThreadGroup) element).setSamplerController(loop);
        }
        if ("httpsampler".equals(type) || "httpdefaults".equals(type)) {
            // HTTP 系元素先置空 Arguments，防 setProperties 前的 NoSuchElementException
            element.setProperty(new TestElementProperty("HTTPsampler.Arguments", new Arguments()));
        }
        ComponentSchema schema = schemaLoader == null ? null : schemaLoader.loadSchema(type);
        List<SchemaBasedPropertyHandler.PropertyGuardViolation> violations =
                propertyHandler.setProperties(element, translate(def), schema);
        if (!violations.isEmpty()) {
            throw new IOException(SchemaBasedPropertyHandler.buildGuardErrorMessage(violations));
        }
        return element;
    }

    /** 已构建节点（全量构建成功后才挂树——schema/守卫失败不留半棵树）。 */
    private record BuiltNode(TestElement element, List<BuiltNode> children) {
    }

    /** EDT 上调用：整棵骨架挂进 TestPlan 节点内（不动既有内容；构建失败时测试树零改动）。 */
    public void insertIntoCurrentPlan(ElementDef rootDef) throws IOException {
        org.apache.jmeter.gui.GuiPackage gui = org.apache.jmeter.gui.GuiPackage.getInstance();
        if (gui == null) {
            throw new IOException("JMeter GUI 不可用，无法插入测试计划");
        }
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(gui.getClass().getClassLoader());
        try {
            // 先全量构建（可能因 schema/守卫失败），再挂树
            BuiltNode built = buildTree(rootDef);
            attach(built, planNodeOf(gui.getTreeModel()), gui);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("插入测试计划失败：" + e.getMessage(), e);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    /**
     * 骨架落点：树模型的 root 是隐形虚拟根，TestPlan 节点是其首子
     * （JMeterTreeModel.getTestPlan 同款寻址）；直接挂 root 会与 TestPlan 平级、
     * 游离在计划外。
     */
    static org.apache.jmeter.gui.tree.JMeterTreeNode planNodeOf(
            org.apache.jmeter.gui.tree.JMeterTreeModel model) throws IOException {
        var root = (org.apache.jmeter.gui.tree.JMeterTreeNode) model.getRoot();
        if (root.getChildCount() == 0) {
            throw new IOException("测试计划树为空，无法插入录制骨架");
        }
        return (org.apache.jmeter.gui.tree.JMeterTreeNode) root.getChildAt(0);
    }

    private BuiltNode buildTree(ElementDef def) throws IOException {
        TestElement el = build(def);
        List<BuiltNode> children = new ArrayList<>();
        for (ElementDef child : def.children()) {
            children.add(buildTree(child));
        }
        return new BuiltNode(el, children);
    }

    private void attach(BuiltNode node, org.apache.jmeter.gui.tree.JMeterTreeNode parent,
                        org.apache.jmeter.gui.GuiPackage gui) throws Exception {
        var treeNode = gui.getTreeModel().addComponent(node.element(), parent);
        for (BuiltNode child : node.children()) {
            attach(child, treeNode, gui);
        }
    }

    /** 另存为完整可打开的 .jmx（TestPlan 根包裹骨架）。 */
    public Path saveAsJmx(List<ElementDef> tree, Path target) throws IOException {
        TestPlan plan = new TestPlan();
        plan.setUserDefinedVariables(new Arguments());
        plan.setName("Recording Plan");
        plan.setProperty(TestElement.TEST_CLASS, TestPlan.class.getName());
        plan.setProperty(TestElement.GUI_CLASS, org.apache.jmeter.control.gui.TestPlanGui.class.getName());
        // ListedHashTree（非默认 HashTree）：HashTree 底层是 HashMap，key 为 TestElement 时
        // 子节点迭代按哈希序——另存的 jmx 采样器顺序会整体乱掉（#序号命名对而位置错乱）。
        // ListedHashTree 以 ArrayList order 维护插入序，序列化按录制时间序输出
        HashTree root = new org.apache.jorphan.collections.ListedHashTree();
        HashTree planTree = root.add(plan);
        try {
            for (ElementDef def : tree) {
                addHashRecursively(def, planTree);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("构建 jmx 元素树失败：" + e.getMessage(), e);
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(SaveService.class.getClassLoader());
        try (OutputStream os = Files.newOutputStream(target)) {
            SaveService.saveTree(root, os);
        } catch (Exception e) {
            throw new IOException("jmx 序列化失败：" + e.getMessage(), e);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
        log.info("Recording skeleton saved to {}", target);
        return target;
    }

    private void addHashRecursively(ElementDef def, HashTree parent) throws Exception {
        TestElement el = build(def);
        HashTree sub = parent.add(el);
        for (ElementDef child : def.children()) {
            addHashRecursively(child, sub);
        }
    }

    // ---- 小工具 ----

    private static int intOf(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static List<?> listOf(Object o) {
        return o instanceof List<?> list ? list : List.of();
    }
}
