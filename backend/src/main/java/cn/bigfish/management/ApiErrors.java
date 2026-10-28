package cn.bigfish.management;
import cn.bigfish.configuration.ConfigStore.ConfigConflictException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ConfigConflictException.class)
    ResponseEntity<?> conflict() { return ResponseEntity.status(409).body(Map.of("code", "CONFIG_CONFLICT", "message", "配置已被其他页面修改，请重新加载后保存。")); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception error) { return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", "配置字段缺失、格式错误或超出范围。")); }
}
