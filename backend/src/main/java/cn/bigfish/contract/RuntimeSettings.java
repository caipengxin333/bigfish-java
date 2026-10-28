package cn.bigfish.contract;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record RuntimeSettings(
    @NotBlank @Size(max = 80) String profileName,
    @NotNull @Size(max = 8000) String persona,
    @NotNull @Pattern(regexp = "[0-9]{0,30}") String roomId,
    @NotBlank @Size(max = 100) String model,
    @Min(32) @Max(2048) int maxOutputTokens,
    @NotBlank @Size(max = 100) String voice,
    boolean readComment,
    @Min(5) @Max(600) int welcomeIntervalSeconds,
    @Min(1) @Max(100) int synthesisQueueCapacity,
    @Min(1) @Max(100) int playbackQueueCapacity,
    @NotNull @Valid Priorities priorities
) {
    public record Priorities(@Min(0) @Max(100) int gift, @Min(0) @Max(100) int comment,
                             @Min(0) @Max(100) int follow, @Min(0) @Max(100) int entrance) {}
    public static RuntimeSettings defaults() {
        return new RuntimeSettings("默认配置", "你是一位简短、友好的虚拟主播。", "", "deepseek-flash",
            256, "zh-CN-XiaoyiNeural", false, 30, 8, 8, new Priorities(60, 50, 10, 5));
    }
}
