package cn.bigfish.contract;

import java.time.Instant;

public record ConfigSnapshot(long version, Instant updatedAt, RuntimeSettings settings) {}
