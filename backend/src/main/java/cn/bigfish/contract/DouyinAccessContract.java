package cn.bigfish.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;
import java.time.Instant;

/** Dedicated local-only credential contract; never part of persisted RuntimeSettings. */
public final class DouyinAccessContract {
    private DouyinAccessContract() {}
    public record Status(String mode,String source,long version,String revision,Instant updatedAt,
                         boolean cookieConfigured,Instant localExpiresAt,String validation,String identityMode,
                         String activeUse,Long activeVersion) {}
    public record Check(String checkId,long configVersion,String configRevision,String source,String mode,
                        String localValidation,String state,String reason,String cleanupError,boolean connected,
                        long comments,long gifts,Instant startedAt,Instant deadline,boolean resourcesReleased) {}
    public record SetGuest(@NotBlank @Size(max=128) String expectedRevision,
                           @NotNull @Pattern(regexp="GUEST") String mode) {}
    public record Replace(@NotBlank @Size(max=128) String expectedRevision,boolean replace,
                          @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(max=16384) String cookie,
                          @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(max=2048) String userAgent,
                          @JsonProperty(access=JsonProperty.Access.WRITE_ONLY) @Pattern(regexp="(?:[1-9][0-9]{0,29})?") String userUniqueIdOverride,
                          @Min(60) @Max(604800) Long localLifetimeSeconds,Instant localExpiresAt) {
        @Override public String toString() { return "DouyinCredentialReplace[redacted]"; }
    }
    public record Clear(@NotBlank @Size(max=128) String expectedRevision,boolean confirm) {}
    public record StartCheck(@NotBlank @Size(max=128) String expectedRevision,
                             @NotBlank @Pattern(regexp="[0-9]{1,30}") String roomId,
                             @Min(1) @Max(30) int durationSeconds) {}
    public record Cancel() {}
}
