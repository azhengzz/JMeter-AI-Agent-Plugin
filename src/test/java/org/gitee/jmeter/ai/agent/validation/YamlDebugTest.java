package org.gitee.jmeter.ai.agent.validation;

import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class YamlDebugTest {
    public static void main(String[] args) throws Exception {
        String yamlPath = "src/main/jmeter-agent/skills/jmeter/references/native/configuration/HeaderManager.schema.yaml";
        String content = Files.readString(Paths.get(yamlPath));

        Yaml yaml = new Yaml();
        Map<String, Object> data = yaml.load(content);

        System.out.println("=== Raw YAML Parsing ===");
        System.out.println("Root keys: " + data.keySet());

        Map<String, Object> component = (Map<String, Object>) data.get("component");
        System.out.println("Component type: " + component.get("type"));
        System.out.println("Component name: " + component.get("name"));

        List<Map<String, Object>> properties = (List<Map<String, Object>>) data.get("properties");
        for (int i = 0; i < properties.size(); i++) {
            Map<String, Object> prop = properties.get(i);
            System.out.printf("\nProperty[%d]:%n", i);
            System.out.println("  name: " + prop.get("name"));
            System.out.println("  type (raw): " + prop.get("type"));
            System.out.println("  type class: " + (prop.get("type") != null ? prop.get("type").getClass() : "null"));

            // Test fromString conversion
            String typeStr = prop.get("type") != null ? prop.get("type").toString() : null;
            ComponentSchema.PropertyType parsedType = ComponentSchema.PropertyType.fromString(typeStr);
            System.out.println("  parsed type: " + parsedType);

            System.out.println("  class: " + prop.get("class"));
            System.out.println("  required: " + prop.get("required"));
            System.out.println("  itemClass: " + prop.get("itemClass"));

            Object itemProps = prop.get("itemProperties");
            if (itemProps instanceof List) {
                List<?> itemList = (List<?>) itemProps;
                System.out.println("  itemProperties count: " + itemList.size());
                for (int j = 0; j < itemList.size(); j++) {
                    Map<String, Object> itemProp = (Map<String, Object>) itemList.get(j);
                    System.out.printf("    item[%d] name: %s, type: %s%n",
                        j, itemProp.get("name"), itemProp.get("type"));
                }
            }
        }
    }
}
