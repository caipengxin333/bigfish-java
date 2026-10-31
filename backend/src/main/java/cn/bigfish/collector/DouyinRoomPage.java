package cn.bigfish.collector;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import static cn.bigfish.collector.DouyinFailure.Code.*;

/** Independent parser of explicitly scoped roomStore hydration data; never executes page JavaScript. */
final class DouyinRoomPage {
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(2 * 1024 * 1024).build()).build());
    private static final Pattern SCRIPTS = Pattern.compile("<script\\b([^>]*)>(.*?)</script\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern RENDER = Pattern.compile("\\bid\\s*=\\s*['\"]RENDER_DATA['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern STORE = Pattern.compile("\"roomStore\"\\s*:\\s*(\\{)");

    static DouyinRoom parse(String html, DouyinRoomInput requested) throws DouyinFailure {
        if (html == null || html.length() > 2 * 1024 * 1024) throw new DouyinFailure(RESPONSE_TOO_LARGE);
        // Only explicit gate documents are classified. Unrecognized variants fail closed as page changes.
        if (html.contains("<title>验证码</title>") || html.contains("<title>安全验证</title>")) throw new DouyinFailure(CHALLENGE_REQUIRED);
        if (html.contains("<title>地区限制</title>")) throw new DouyinFailure(REGION_RESTRICTED);
        if (html.contains("<title>登录抖音</title>")) throw new DouyinFailure(AUTH_REQUIRED);
        List<DouyinRoom> found = new ArrayList<>();
        var scripts = SCRIPTS.matcher(html);
        while (scripts.find()) {
            String source = scripts.group(2).strip();
            try {
                if (RENDER.matcher(scripts.group(1)).find()) {
                    // URLDecoder's '+' conversion is not appropriate to encodeURIComponent hydration.
                    source = URLDecoder.decode(source.replace("+", "%2B"), StandardCharsets.UTF_8);
                    scan(source, requested, found);
                } else if (source.startsWith("self.__pace_f.push(")) {
                    int begin = source.indexOf('['), end = source.lastIndexOf(']');
                    if (begin >= 0 && end > begin) scanStrings(JSON.readTree(source.substring(begin, end + 1)), requested, found, 0);
                } else if (source.startsWith("{")) scan(source, requested, found);
            } catch (DouyinFailure failure) { throw failure; }
            catch (Exception ignored) { throw new DouyinFailure(PAGE_STRUCTURE_CHANGED); }
        }
        if (found.isEmpty() || found.stream().anyMatch(room -> !room.equals(found.getFirst()))) throw new DouyinFailure(PAGE_STRUCTURE_CHANGED);
        return found.getFirst();
    }

    private static void scanStrings(JsonNode node, DouyinRoomInput request, List<DouyinRoom> found, int depth) throws Exception {
        if (depth > 16) throw new DouyinFailure(PAGE_STRUCTURE_CHANGED);
        if (node.isTextual()) scan(node.textValue(), request, found);
        else if (node.isContainerNode()) for (JsonNode child : node) scanStrings(child, request, found, depth + 1);
    }

    private static void scan(String source, DouyinRoomInput request, List<DouyinRoom> found) throws Exception {
        var stores = STORE.matcher(source);
        while (stores.find()) {
            if (found.size() >= 8) throw new DouyinFailure(PAGE_STRUCTURE_CHANGED);
            // Read exactly one object, preserving quoted braces and escapes, rather than a global roomId regex.
            JsonNode store;
            try (var parser = JSON.createParser(source.substring(stores.start(1)))) { store = JSON.readTree(parser); }
            JsonNode info = store.path("roomInfo"), room = info.path("room"), anchor = info.path("anchor");
            String internal = room.path("id_str").asText("");
            if (internal.isEmpty()) internal = info.path("roomId").asText("");
            String alternate = info.path("roomId").asText(internal);
            if (!internal.equals(alternate) || !internal.matches("[0-9]{1,30}")) throw new DouyinFailure(PAGE_STRUCTURE_CHANGED);
            String web = anchor.path("web_rid").asText(request.webRoomId());
            if (!web.equals(request.webRoomId())) throw new DouyinFailure(PAGE_STRUCTURE_CHANGED);
            // This is room.status, not /web/enter's room_status (which has different values).
            int status = room.path("status").asInt(-1);
            if (status != 2 && status != 4) throw new DouyinFailure(PAGE_STRUCTURE_CHANGED);
            String id = anchor.path("id_str").asText("");
            String name = anchor.path("nickname").asText("");
            if (!id.matches("[0-9]{1,30}") || name.isBlank()) throw new DouyinFailure(PAGE_STRUCTURE_CHANGED);
            found.add(new DouyinRoom(request.webRoomId(), internal, id, name, status == 2));
        }
    }
}
