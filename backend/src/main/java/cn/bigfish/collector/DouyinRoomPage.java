package cn.bigfish.collector;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
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
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(64).maxStringLength(2 * 1024 * 1024).build()).build());
    private static final Pattern SCRIPTS = Pattern.compile("<script\\b([^>]*)>(.*?)</script\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern RENDER = Pattern.compile("\\bid\\s*=\\s*['\"]RENDER_DATA['\"]", Pattern.CASE_INSENSITIVE);
    private static final Pattern STORE = Pattern.compile("\"roomStore\"\\s*:\\s*(\\{)");
    private static final Pattern TITLE = Pattern.compile("<title\\s*>([^<]{0,128})</title\\s*>", Pattern.CASE_INSENSITIVE);

    static DouyinRoom parse(String html, DouyinRoomInput requested) throws DouyinFailure {
        if (html == null || html.length() > 2 * 1024 * 1024) throw new DouyinFailure(RESPONSE_TOO_LARGE);
        checkGate(html);
        List<DouyinRoom> found = new ArrayList<>();
        var scripts = SCRIPTS.matcher(html);
        while (scripts.find()) {
            String source = scripts.group(2).strip();
            try {
                if (RENDER.matcher(scripts.group(1)).find()) {
                    // URLDecoder's '+' conversion is not appropriate to encodeURIComponent hydration.
                    source = URLDecoder.decode(source.replace("+", "%2B"), StandardCharsets.UTF_8);
                    scan(jsonEntities(source), requested, found);
                } else if (source.startsWith("self.__pace_f.push(")) {
                    int begin = source.indexOf('['), end = source.lastIndexOf(']');
                    if (begin >= 0 && end > begin) scanStrings(JSON.readTree(source.substring(begin, end + 1)), requested, found, 0);
                } else if (source.startsWith("{")) scan(jsonEntities(source), requested, found);
            } catch (DouyinFailure failure) { throw failure; }
            catch (Exception ignored) { throw new DouyinFailure(PAGE_STRUCTURE_CHANGED); }
        }
        if (found.isEmpty() || found.stream().anyMatch(room -> !room.equals(found.getFirst()))) throw new DouyinFailure(PAGE_STRUCTURE_CHANGED);
        return found.getFirst();
    }

    static void checkGate(String html) throws DouyinFailure {
        if (html == null || html.length() > 2 * 1024 * 1024) throw new DouyinFailure(RESPONSE_TOO_LARGE);
        var title = TITLE.matcher(html);
        if (!title.find()) return;
        String text = title.group(1).strip();
        if (text.matches("(?:验证码|安全验证)(?:\\s*[-—]\\s*抖音)?")) throw new DouyinFailure(CHALLENGE_REQUIRED);
        if (text.equals("地区限制")) throw new DouyinFailure(REGION_RESTRICTED);
        if (text.equals("登录抖音")) throw new DouyinFailure(AUTH_REQUIRED);
    }

    /** Read only an explicit visitor field in supported hydration, never an anchor ID or a generated fallback. */
    static String guestId(String html) throws DouyinFailure {
        try {
            String id = visitorId(html);
            if (id == null) throw new DouyinFailure(GUEST_ID_MISSING);
            return id;
        } catch (DouyinFailure failure) {
            if (failure.code() == EXTERNAL_ID_INVALID || failure.code() == EXTERNAL_ID_CONFLICT)
                throw new DouyinFailure(PAGE_STRUCTURE_CHANGED); // Keep guest error compatibility.
            throw failure;
        }
    }

    /** Null means no explicit visitor field. Malformed/conflicting fields never become a missing field. */
    static String visitorId(String html) throws DouyinFailure {
        checkGate(html);
        var ids = new java.util.HashSet<String>();
        var scripts = SCRIPTS.matcher(html);
        while (scripts.find()) {
            String source = scripts.group(2).strip();
            try {
                if (RENDER.matcher(scripts.group(1)).find()) {
                    source = URLDecoder.decode(source.replace("+", "%2B"), StandardCharsets.UTF_8);
                } else if (source.startsWith("self.__pace_f.push(")) {
                    int begin = source.indexOf('['), end = source.lastIndexOf(']');
                    if (begin < 0 || end <= begin) throw new DouyinFailure(PAGE_STRUCTURE_CHANGED);
                    source = source.substring(begin, end + 1);
                } else if (!source.startsWith("{")) continue;
                collectGuestIds(JSON.readTree(jsonEntities(source)), ids, 0);
            } catch (DouyinFailure failure) { throw failure; }
            catch (Exception ignored) { throw new DouyinFailure(PAGE_STRUCTURE_CHANGED); }
        }
        return ids.isEmpty() ? null : ids.iterator().next();
    }

    private static void collectGuestIds(JsonNode node, java.util.Set<String> ids, int depth) throws Exception {
        if (node == null) return;
        // The real room hydration includes unrelated display arrays deeper than 24 levels.
        // Keep traversal bounded by the same depth budget as JSON reading instead of rejecting those pages.
        if (depth > 64) throw new DouyinFailure(PAGE_STRUCTURE_CHANGED);
        if (node.isObject()) {
            for (var field : node.properties()) {
                if (field.getKey().equals("user_unique_id")) {
                    JsonNode value = field.getValue();
                    String id = value.asText("");
                    if ((!value.isTextual() && !value.isIntegralNumber()) || !id.matches("[1-9][0-9]{0,29}")) {
                        throw new DouyinFailure(EXTERNAL_ID_INVALID);
                    }
                    ids.add(id);
                    if (ids.size() > 1) throw new DouyinFailure(EXTERNAL_ID_CONFLICT);
                } else collectGuestIds(field.getValue(), ids, depth + 1);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) collectGuestIds(child, ids, depth + 1);
        } else if (node.isTextual()) {
            String source = node.textValue().strip();
            // Next/pace stream chunks may prefix JSON with a numeric chunk identifier.
            source = source.replaceFirst("^[0-9]{1,8}:", "");
            if ((source.startsWith("{") || source.startsWith("[")) && source.contains("user_unique_id")) {
                collectGuestIds(JSON.readTree(jsonEntities(source)), ids, depth + 1);
            }
        }
    }

    private static void scanStrings(JsonNode node, DouyinRoomInput request, List<DouyinRoom> found, int depth) throws Exception {
        if (depth > 16) throw new DouyinFailure(PAGE_STRUCTURE_CHANGED);
        if (node.isTextual()) scan(jsonEntities(node.textValue()), request, found);
        else if (node.isContainerNode()) for (JsonNode child : node) scanStrings(child, request, found, depth + 1);
    }

    // Only decode an explicitly entity-quoted JSON object, once. Ordinary script JSON and its text
    // values remain untouched; this never evaluates script or expands arbitrary HTML attributes.
    private static String jsonEntities(String source) {
        if (!source.matches("(?s)^\\s*(?:[0-9]{1,8}:)?\\{\\s*(?:&quot;|&#34;|&#x22;).*")) return source;
        return source.replace("&quot;", "\"").replace("&#34;", "\"").replace("&#x22;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&apos;", "'").replace("&amp;", "&");
    }

    private static void scan(String source, DouyinRoomInput request, List<DouyinRoom> found) throws Exception {
        var stores = STORE.matcher(source);
        while (stores.find()) {
            if (found.size() >= 8) throw new DouyinFailure(PAGE_STRUCTURE_CHANGED);
            // Read exactly one object, preserving quoted braces and escapes, rather than a global roomId regex.
            JsonNode store;
            try (var parser = JSON.createParser(source.substring(stores.start(1)))) { store = JSON.readTree(parser); }
            JsonNode info = store.path("roomInfo"), room = info.path("room"), anchor = info.path("anchor");
            // Pace hydration emits an empty bootstrap store before the populated room store.
            // Only that exact placeholder is ignorable; partial/null/conflicting metadata still fails closed.
            if (info.isObject() && info.isEmpty()) continue;
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
