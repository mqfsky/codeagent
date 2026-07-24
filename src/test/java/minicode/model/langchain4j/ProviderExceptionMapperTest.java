package minicode.model.langchain4j;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import minicode.model.ProviderRequestException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderExceptionMapperTest {
    private final ProviderExceptionMapper mapper = new ProviderExceptionMapper();

    @Test
    void preservesRetriableClassificationNestedHttpStatusAndCause() {
        RetriableException exception = new RetriableException(
                "temporary provider failure",
                new RuntimeException(new HttpException(429, "rate limited"))
        );

        ProviderRequestException mapped = mapper.map(exception);

        assertEquals(Optional.of(429), mapped.statusCode());
        assertTrue(mapped.retryable());
        assertSame(exception, mapped.getCause());
        assertEquals("temporary provider failure", mapped.getMessage());
    }

    @Test
    void preservesNonRetriableClassificationAndHttpStatus() {
        NonRetriableException exception = new NonRetriableException(
                "invalid request",
                new HttpException(400, "bad input")
        );

        ProviderRequestException mapped = mapper.map(exception);

        assertEquals(Optional.of(400), mapped.statusCode());
        assertFalse(mapped.retryable());
        assertSame(exception, mapped.getCause());
    }

    @Test
    void classifiesBareHttpExceptionFromStatus() {
        ProviderRequestException serverError = mapper.map(new HttpException(503, "unavailable"));
        ProviderRequestException clientError = mapper.map(new HttpException(404, "not found"));

        assertEquals(Optional.of(503), serverError.statusCode());
        assertTrue(serverError.retryable());
        assertEquals(Optional.of(404), clientError.statusCode());
        assertFalse(clientError.retryable());
    }

    @Test
    void mapsUnknownRuntimeFailureAsNonRetriableWithoutStatus() {
        IllegalStateException exception = new IllegalStateException("unexpected");

        ProviderRequestException mapped = mapper.map(exception);

        assertEquals(Optional.empty(), mapped.statusCode());
        assertFalse(mapped.retryable());
        assertSame(exception, mapped.getCause());
    }

    @Test
    void classifiesNestedIoFailureAsRetryableTransportError() {
        RuntimeException exception = new RuntimeException(
                "HTTP transport failed",
                new IOException("connection reset"));

        ProviderRequestException mapped = mapper.map(exception);

        assertEquals(Optional.empty(), mapped.statusCode());
        assertTrue(mapped.retryable());
        assertSame(exception, mapped.getCause());
    }

    @Test
    void doesNotWrapExistingProviderRequestException() {
        ProviderRequestException exception = new ProviderRequestException(
                "already mapped", Optional.of(502), true);

        assertSame(exception, mapper.map(exception));
    }
}
