package org.gitee.jmeter.ai.agent.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of parameter validation.
 */
public class ValidationResult {
    private final boolean valid;
    private final List<String> errors;

    private ValidationResult(boolean valid, List<String> errors) {
        this.valid = valid;
        this.errors = errors != null ? errors : Collections.emptyList();
    }

    public boolean isValid() {
        return valid;
    }

    public List<String> getErrors() {
        return errors;
    }

    public static ValidationResult valid() {
        return new ValidationResult(true, Collections.emptyList());
    }

    public static ValidationResult invalid(String error) {
        return new ValidationResult(false, Collections.singletonList(error));
    }

    public static ValidationResult invalid(List<String> errors) {
        return new ValidationResult(false, errors);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final List<String> errors = new ArrayList<>();

        public Builder addError(String error) {
            this.errors.add(error);
            return this;
        }

        public Builder addErrors(List<String> errors) {
            this.errors.addAll(errors);
            return this;
        }

        public ValidationResult build() {
            return new ValidationResult(errors.isEmpty(), errors.isEmpty() ? null : errors);
        }
    }

    @Override
    public String toString() {
        return "ValidationResult{" +
                "valid=" + valid +
                ", errors=" + errors +
                '}';
    }
}
