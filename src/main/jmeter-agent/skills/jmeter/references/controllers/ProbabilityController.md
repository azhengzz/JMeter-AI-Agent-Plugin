# Probability Controller

## Description

The Probability Controller randomly selects and executes one of its child Probability Controllers based on their assigned weights. This enables weighted random distribution of test scenarios, useful for simulating realistic traffic patterns where different operations occur with different frequencies.

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| ProbabilityController.weight | String | No | 0 | Weight value for probability calculation. Higher weight means higher chance of being selected |

## How It Works

1. The controller calculates the total weight of all direct child Probability Controllers
2. It generates a random number between 0 and the total weight
3. It selects the child whose weight range covers the random number
4. Only the selected child (and its subtree) executes; other children are skipped
5. On each iteration, a new random selection is made

## Usage Examples

### Example 1: Browse vs Purchase Traffic (80/20 Split)

```
create_jmeter_element with:
- elementType: "probabilitycontroller"
- elementName: "Traffic Router"

// 80% weight - Browse products
create_jmeter_element with:
- elementType: "probabilitycontroller"
- elementName: "Browse Products (80%)"
- properties:
    ProbabilityController.weight: "80"

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "GET Product List"

// 20% weight - Purchase
create_jmeter_element with:
- elementType: "probabilitycontroller"
- elementName: "Purchase (20%)"
- properties:
    ProbabilityController.weight: "20"

create_jmeter_element with:
  elementType: "httpsampler"
  elementName: "POST Place Order"
```

### Example 2: Multi-Scenario Distribution

```
create_jmeter_element with:
- elementType: "probabilitycontroller"
- elementName: "User Behavior Mix"

// Scenario A - weight 50
create_jmeter_element with:
- elementType: "probabilitycontroller"
- elementName: "Search (50%)"
- properties:
    ProbabilityController.weight: "50"

// Scenario B - weight 30
create_jmeter_element with:
- elementType: "probabilitycontroller"
- elementName: "Add to Cart (30%)"
- properties:
    ProbabilityController.weight: "30"

// Scenario C - weight 20
create_jmeter_element with:
- elementType: "probabilitycontroller"
- elementName: "Checkout (20%)"
- properties:
    ProbabilityController.weight: "20"
```

### Example 3: Nested Probability Controllers

```
create_jmeter_element with:
- elementType: "probabilitycontroller"
- elementName: "Main Router"

create_jmeter_element with:
- elementType: "probabilitycontroller"
- elementName: "API v1 (70%)"
- properties:
    ProbabilityController.weight: "70"

  // Nested sub-scenarios inside API v1
  create_jmeter_element with:
  - elementType: "probabilitycontroller"
  - elementName: "Sub-scenario A (60%)"
  - properties:
      ProbabilityController.weight: "60"

  create_jmeter_element with:
  - elementType: "probabilitycontroller"
  - elementName: "Sub-scenario B (40%)"
  - properties:
      ProbabilityController.weight: "40"
```

## Best Practices

1. **Use integer weights**: E.g., `80` and `20` instead of `0.8` and `0.2` for clarity
2. **Ensure weights sum to 100**: Makes it intuitive to understand percentages (though not required)
3. **Name with percentage**: Include the percentage in the element name for readability, e.g., `"Browse (80%)"`
4. **Keep scenarios independent**: Each child Probability Controller should contain a self-contained test scenario
5. **Nest for complex distributions**: Use nested Probability Controllers for multi-level random selection

## Notes

- Only direct child Probability Controllers participate in the weight calculation; other element types (samplers, controllers) are always executed
- If no weight is set, the default is `0`, meaning the child is never selected
- The random selection is re-evaluated on each iteration
- This is a Gitee QA extension component and requires the corresponding plugin to be installed
