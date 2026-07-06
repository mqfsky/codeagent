package minicode.permissions.model;

import java.util.List;
import java.util.Objects;

/**
 * 命令可执行文件和参数签名。
 *
 * @param executable 可执行文件名称或路径
 * @param arguments 命令参数列表
 */
public record CommandSignature(String executable, List<String> arguments) {
    public CommandSignature {
        if (Objects.requireNonNull(executable, "executable").isBlank()) {
            throw new IllegalArgumentException("executable must not be blank");
        }
        arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }
}
