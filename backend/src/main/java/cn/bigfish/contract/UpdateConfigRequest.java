package cn.bigfish.contract;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
public record UpdateConfigRequest(@Min(1) long expectedVersion, @NotNull @Valid RuntimeSettings settings) {}
