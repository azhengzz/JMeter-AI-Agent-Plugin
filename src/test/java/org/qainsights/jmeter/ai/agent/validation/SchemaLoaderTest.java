package org.qainsights.jmeter.ai.agent.validation;

import org.qainsights.jmeter.ai.agent.tools.ValidationResult;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

public class SchemaLoaderTest {
    public static void main(String[] args) {
        Path skillsDir = Paths.get("src/main/jmeter-agent/skills");
        ComponentSchemaLoader loader = new ComponentSchemaLoader(skillsDir);

        // Test loading headermanager schema
        ComponentSchema schema = loader.loadSchema("headermanager");
        if (schema == null) {
            System.out.println("ERROR: Schema for 'headermanager' not found!");
            System.out.println("Available schemas: " + loader.getLoadedSchemaTypes());
            return;
        }

        System.out.println("=== Schema for headermanager ===");
        System.out.println("Component Type: " + schema.getComponentType());
        System.out.println("Component Name: " + schema.getComponentName());
        System.out.println("Description: " + schema.getDescription());
        System.out.println("\n=== Properties ===");

        for (ComponentSchema.PropertyDefinition prop : schema.getProperties()) {
            System.out.printf("- Name: %-30s Type: %-10s Required: %b%n",
                prop.getName(), prop.getType(), prop.isRequired());

            if (prop.hasItemProperties()) {
                System.out.println("  Item Properties:");
                for (ComponentSchema.PropertyDefinition itemProp : prop.getItemProperties()) {
                    System.out.printf("    - %s: Type=%s, Required=%b%n",
                        itemProp.getName(), itemProp.getType(), itemProp.isRequired());
                }
            }
        }

        // Test validation with array data
        System.out.println("\n=== Testing Validation ===");
        ComponentValidator validator = new ComponentValidator(loader);

        // Create test data - array of headers (using wrong property names)
        Map<String, Object> properties = new HashMap<>();
        List<Map<String, Object>> headers = new ArrayList<>();

        Map<String, Object> header1 = new HashMap<>();
        header1.put("Name", "Cookie");
        header1.put("Value", "xxx-xxxx-xx");

        Map<String, Object> header2 = new HashMap<>();
        header2.put("Name", "Session-id");
        header2.put("Value", "1234567");

        headers.add(header1);
        headers.add(header2);

        properties.put("HeaderManager.headers", headers);

        ValidationResult result = validator.validate("headermanager", properties);
        if (result.isValid()) {
            System.out.println("Validation PASSED!");
        } else {
            System.out.println("Validation FAILED:");
            for (String error : result.getErrors()) {
                System.out.println("  - " + error);
            }
        }
    }
}
