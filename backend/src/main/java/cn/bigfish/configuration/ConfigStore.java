package cn.bigfish.configuration;

import cn.bigfish.contract.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConfigStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public ConfigStore(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }

    public ConfigSnapshot current() {
        jdbc.update("INSERT OR IGNORE INTO app_config(id,version,content_json,updated_at) VALUES(1,1,?,?)",
            encode(RuntimeSettings.defaults()), Instant.now().toString());
        return jdbc.queryForObject("SELECT version,content_json,updated_at FROM app_config WHERE id=1", (rs, row) ->
            new ConfigSnapshot(rs.getLong("version"), Instant.parse(rs.getString("updated_at")), decode(rs.getString("content_json"))));
    }
    public ConfigSnapshot update(UpdateConfigRequest request) {
        current();
        var now = Instant.now();
        int rows = jdbc.update("UPDATE app_config SET version=version+1,content_json=?,updated_at=? WHERE id=1 AND version=?",
            encode(request.settings()), now.toString(), request.expectedVersion());
        if (rows != 1) throw new ConfigConflictException();
        return new ConfigSnapshot(request.expectedVersion()+1, now, request.settings());
    }
    private String encode(RuntimeSettings settings) {
        try { return mapper.writeValueAsString(settings); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize settings", e); }
    }
    private RuntimeSettings decode(String value) {
        try { return mapper.readValue(value, RuntimeSettings.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored settings are invalid", e); }
    }
    public static class ConfigConflictException extends RuntimeException {}
}
