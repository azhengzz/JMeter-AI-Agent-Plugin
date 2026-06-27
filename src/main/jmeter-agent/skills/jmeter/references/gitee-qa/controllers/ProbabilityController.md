# Probability Controller
> **Source**: Gitee QA extension (third-party plugin `com.gitee.qa.jmeter`, requires the corresponding plugin)

## Description

The Probability Controller randomly selects and executes **one** of its child Probability Controllers based on their assigned weights. This enables weighted random distribution of test scenarios, useful for simulating realistic traffic patterns where different operations occur with different frequencies.

## ⚠️ Important: How It Works

**Key Requirement**: Probability Controller **must have a parent Probability Controller** to function correctly.

### Weight Calculation Mechanism

1. A parent Probability Controller collects all direct child Probability Controllers
2. Calculates total weight = sum of all child weights
3. Generates a random number between 0 and total weight
4. Selects the child whose cumulative weight range covers the random number
5. Only the selected child executes; other children are skipped
6. On each iteration, a new random selection is made

### Weight Distribution Formula

For weights 60/30/10:
- GET selected if: `0 ≤ random < 60` (probability = 60/100 = 60%)
- POST selected if: `60 ≤ random < 90` (probability = 30/100 = 30%)
- DELETE selected if: `90 ≤ random ≤ 100` (probability = 10/100 = 10%)

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| ProbabilityController.weight | String | No | 0 | Weight value for probability calculation. Higher weight means higher chance of being selected |

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

1. **Always nest under a parent Probability Controller**: This is the most critical requirement. Without a parent PC, weights will not work.

2. **Use integer weights**: E.g., `80` and `20` instead of `0.8` and `0.2` for clarity

3. **Ensure weights sum to 100**: Makes it intuitive to understand percentages (though not required)

4. **Name with percentage**: Include the percentage in the element name for readability, e.g., `"Browse (80%)"`

5. **Keep scenarios independent**: Each child Probability Controller should contain a self-contained test scenario

6. **Nest for complex distributions**: Use nested Probability Controllers for multi-level random selection

7. **Parent PC can be empty**: The parent Probability Controller doesn't need a weight - it just needs to exist as a container. It can have weight `0` or no weight set at all.

8. **Child elements are always executed**: Only direct child Probability Controllers participate in random selection; samplers and other controllers inside a child PC will always execute if that child is selected.

## ⚠️ Common Pitfall

**Incorrect Usage**: Placing Probability Controllers directly under a Thread Group or Simple Controller will NOT work as expected. The weights will not be respected, and distribution will be uneven.

**Correct Usage**: Always nest Probability Controllers under a parent Probability Controller.

```
❌ Incorrect:
Thread Group
└── Probability Controller (weight: 60)
    └── HTTP Request GET
└── Probability Controller (weight: 30)
    └── HTTP Request POST

✓ Correct:
Thread Group
└── Simple Controller (optional wrapper)
    └── Probability Controller (parent - no weight needed)
        └── Probability Controller (weight: 60)
            └── HTTP Request GET
        └── Probability Controller (weight: 30)
            └── HTTP Request POST
```

The parent Probability Controller can have weight `0` or be empty - it only acts as a container for random selection.
