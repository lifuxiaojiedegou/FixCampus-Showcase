package com.fixcampus.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class CodePointLengthValidator implements ConstraintValidator<CodePointLength, String> {
    private int min;
    private int max;

    @Override
    public void initialize(CodePointLength constraint) {
        min = constraint.min();
        max = constraint.max();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) { return true; }
        int length = value.codePointCount(0, value.length());
        return length >= min && length <= max;
    }
}
