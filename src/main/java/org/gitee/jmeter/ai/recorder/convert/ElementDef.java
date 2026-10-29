package org.gitee.jmeter.ai.recorder.convert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元素定义（转换器输出的纯数据树，组 4 负责经 JMeterElementManager/schema 校验落到测试树）。
 *
 * <p>属性用平铺键名（method/path/headers/cookies/...），与 JMeter 属性名的映射是落树层的职责——
 * 转换器保持零 JMeter 依赖、可全量单测。
 */
public record ElementDef(String elementType, String name, Map<String, Object> props, List<ElementDef> children) {

    public static ElementDef of(String elementType, String name) {
        return new ElementDef(elementType, name, new LinkedHashMap<>(), new ArrayList<>());
    }

    public static ElementDef of(String elementType, String name, Map<String, Object> props) {
        return new ElementDef(elementType, name, new LinkedHashMap<>(props), new ArrayList<>());
    }

    public ElementDef child(ElementDef child) {
        children.add(child);
        return this;
    }
}
