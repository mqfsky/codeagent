package minicode.model.langchain4j;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RetriableException;
import minicode.model.ProviderRequestException;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 把 LangChain4j 异常转换成 CodeAgent 统一的 Provider 异常，并保留重试分类、HTTP 状态和原因链。
 */
public final class ProviderExceptionMapper {

    /**
     * 沿异常原因链提取最有用的 Provider 错误信息。
     *
     * @param exception LangChain4j 或底层 HTTP Client 抛出的运行时异常
     * @return Runtime 已认识的 {@link ProviderRequestException}
     */
    public ProviderRequestException map(RuntimeException exception) {
        RuntimeException actualException = Objects.requireNonNull(exception, "exception");
        if (actualException instanceof ProviderRequestException providerRequestException) {
            // 已经完成统一映射的异常直接返回，避免丢失原状态码、retryable 和 cause。
            return providerRequestException;
        }

        Optional<Integer> statusCode = Optional.empty();
        Boolean explicitRetryable = null;
        boolean transportFailure = false;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = actualException;
        while (current != null && visited.add(current)) {
            // 原因链中离外层最近的显式分类优先。
            // 例如 NonRetriableException 包装 IOException 时，仍应保持“不可重试”。
            if (explicitRetryable == null) {
                if (current instanceof RetriableException) {
                    explicitRetryable = true;
                } else if (current instanceof NonRetriableException) {
                    explicitRetryable = false;
                }
            }

            // HTTP 状态经常被 RateLimitException 等异常包在内部，需要遍历 cause 才能取到。
            if (statusCode.isEmpty() && current instanceof HttpException httpException) {
                statusCode = Optional.of(httpException.statusCode());
            }

            // JDK HTTP 在连接重置等场景可能只留下普通 RuntimeException + IOException cause。
            if (current instanceof IOException) {
                transportFailure = true;
            }
            current = current.getCause();
        }

        // 判断优先级：LangChain4j 显式分类 > HTTP 状态码 > 是否为底层 I/O 故障。
        // 没有任何证据时按不可重试处理，避免无根据地放大请求次数。
        boolean retryable = explicitRetryable != null
                ? explicitRetryable
                : statusCode.map(ProviderExceptionMapper::isRetryableStatus)
                        .orElse(transportFailure);
        return new ProviderRequestException(
                message(actualException),
                statusCode,
                retryable,
                actualException
        );
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500 && statusCode < 600;
    }

    private static String message(Throwable throwable) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        while (current != null && visited.add(current)) {
            // 优先保留最外层可读诊断；只有外层无消息时才继续查看 cause。
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                return current.getMessage();
            }
            current = current.getCause();
        }
        return "LangChain4j model request failed";
    }
}
