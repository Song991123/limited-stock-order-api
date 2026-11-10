package dev.mentoring.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import dev.mentoring.orders.controller.CsrfController;
import dev.mentoring.orders.controller.OrderController;
import dev.mentoring.orders.controller.ProductController;
import dev.mentoring.orders.dto.request.CreateOrderRequest;
import dev.mentoring.orders.dto.response.CsrfResponse;
import dev.mentoring.orders.dto.response.OrderResponse;
import dev.mentoring.orders.dto.response.ProductResponse;
import jakarta.validation.Valid;
import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.csrf.CsrfToken;

/** 제품 클래스 자동 탐색 후 HTTP 처리, 업무 처리와 SQL 저장의 역할 구분 확인. */
class PackageStructureTest {
    private static final JavaClasses APPLICATION_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("dev.mentoring.orders");

    private static final ArchRule INTERNAL_TYPES_WITHOUT_HTTP = noClasses()
            .that().resideInAnyPackage("..service..", "..repository..", "..domain..", "..security..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "dev.mentoring.orders.dto..", "dev.mentoring.orders.controller..", "dev.mentoring.orders.web..",
                    "org.springframework.http..", "org.springframework.web..", "org.springframework.security.web..", "jakarta.servlet..")
            .as("서비스와 저장소 및 내부 자료형은 HTTP DTO와 웹 처리에 의존하지 않음");

    private static final ArchRule CONTROLLERS_WITHOUT_REPOSITORIES = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("dev.mentoring.orders.repository..")
            .as("컨트롤러는 저장소를 직접 호출하지 않음");

    @Test
    void internalTypesDoNotExposeHttpDtosInTheirSignatures() {
        INTERNAL_TYPES_WITHOUT_HTTP.check(APPLICATION_CLASSES);
    }

    @Test
    void controllersKeepRequestValidationAndReturnResponseDtos() throws Exception {
        var create = OrderController.class.getDeclaredMethod("placeOrder",
                Principal.class, String.class, CreateOrderRequest.class);
        assertThat(create.getParameters()[2].isAnnotationPresent(Valid.class)).isTrue();
        assertThat(create.getGenericReturnType().getTypeName()).contains(OrderResponse.class.getName())
                .doesNotContain("?");
        assertThat(OrderController.class.getDeclaredMethod("getOrder", Principal.class, long.class).getReturnType())
                .isEqualTo(OrderResponse.class);
        assertThat(ProductController.class.getDeclaredMethod("getProduct", long.class).getReturnType())
                .isEqualTo(ProductResponse.class);
        assertThat(CsrfController.class.getDeclaredMethod("getCsrfToken", CsrfToken.class).getReturnType())
                .isEqualTo(CsrfResponse.class);
    }

    @Test
    void controllersCallServicesInsteadOfRepositories() {
        CONTROLLERS_WITHOUT_REPOSITORIES.check(APPLICATION_CLASSES);
    }

    @Test
    void controllersAndServicesDoNotExecuteSql() {
        noClasses().that().resideInAnyPackage("..controller..", "..service..", "..security..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..", "javax.sql..")
                .check(APPLICATION_CLASSES);
    }

    @Test
    void repositoriesDoNotDependOnServicesOrAuthenticationFramework() {
        noClasses().that().resideInAPackage("..repository..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "dev.mentoring.orders.service..", "dev.mentoring.orders.security..", "org.springframework.security..")
                .check(APPLICATION_CLASSES);
    }

    @Test
    void domainTypesDoNotDependOnFrameworkOrStorage() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework..", "dev.mentoring.orders.repository..", "dev.mentoring.orders.service..",
                        "dev.mentoring.orders.security..")
                .check(APPLICATION_CLASSES);
    }

    @Test
    void configurationClassesDoNotContainJdbcQueries() {
        noClasses().that().areAnnotatedWith(Configuration.class)
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc.core..", "java.sql..")
                .check(APPLICATION_CLASSES);
    }

    @Test
    void automaticallyDiscoveredServiceWithBodyOnlyDtoUseIsRejected() {
        var fixtures = new ClassFileImporter().importPackages("architecturefixtures.service");
        var result = INTERNAL_TYPES_WITHOUT_HTTP.evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains("ServiceWithDtoDependency", "ErrorResponse");
    }

    @Test
    void automaticallyDiscoveredControllerWithRepositoryUseIsRejected() {
        var fixtures = new ClassFileImporter().importPackages("architecturefixtures.controller");
        var result = CONTROLLERS_WITHOUT_REPOSITORIES.evaluate(fixtures);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().toString()).contains("ControllerWithRepositoryDependency", "OrderRepository");
    }
}
