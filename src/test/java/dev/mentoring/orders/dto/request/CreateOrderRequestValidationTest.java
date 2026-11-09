package dev.mentoring.orders.dto.request;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.lang.annotation.Annotation;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 필수값 누락과 0 이하 입력을 각각 어느 Bean Validation 조건이 거절하는지 확인. */
class CreateOrderRequestValidationTest {
    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (validatorFactory != null) validatorFactory.close();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidOrders")
    void rejectsMissingAndNonPositiveFields(String description, Long productId, Integer quantity,
            String expectedField, Class<? extends Annotation> expectedConstraint) {
        var violations = validator.validate(new CreateOrderRequest(productId, quantity));

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo(expectedField);
            assertThat(violation.getConstraintDescriptor().getAnnotation().annotationType())
                    .isEqualTo(expectedConstraint);
        });
    }

    @Test
    void acceptsPositiveNumbersWithoutInventingAStockLimit() {
        assertThat(validator.validate(new CreateOrderRequest(1L, 1))).isEmpty();
        assertThat(validator.validate(new CreateOrderRequest(Long.MAX_VALUE, Integer.MAX_VALUE))).isEmpty();
    }

    private static Stream<Arguments> invalidOrders() {
        return Stream.of(
                Arguments.of("missing-product-id", null, 1, "productId", NotNull.class),
                Arguments.of("zero-product-id", 0L, 1, "productId", Positive.class),
                Arguments.of("negative-product-id", -1L, 1, "productId", Positive.class),
                Arguments.of("missing-quantity", 1L, null, "quantity", NotNull.class),
                Arguments.of("zero-quantity", 1L, 0, "quantity", Positive.class),
                Arguments.of("negative-quantity", 1L, -1, "quantity", Positive.class));
    }
}
