package org.gitee.jmeter.ai.agent.validation;

public class ComponentSchemaTypeTest {
    public static void main(String[] args) {
        System.out.println("=== Testing PropertyType.fromString() ===");
        testType("Array");
        testType("array");
        testType("ARRAY");
        testType("Object");
        testType("String");
        testType("Integer");
        testType("Boolean");
        testType("Number");
        testType(null);
        testType("InvalidType");
    }

    private static void testType(String typeStr) {
        ComponentSchema.PropertyType type = ComponentSchema.PropertyType.fromString(typeStr);
        String displayStr = (typeStr == null) ? "null" : "\"" + typeStr + "\"";
        System.out.printf("fromString(%-15s) => %s%n", displayStr, type);
    }
}