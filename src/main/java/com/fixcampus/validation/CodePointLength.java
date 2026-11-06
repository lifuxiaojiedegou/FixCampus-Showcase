package com.fixcampus.validation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/** Match MySQL utf8mb4 CHAR_LENGTH, including supplementary Unicode characters. */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = CodePointLengthValidator.class)
public @interface CodePointLength {
    String message() default "字符长度不符合要求";
    int min() default 0;
    int max();
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
