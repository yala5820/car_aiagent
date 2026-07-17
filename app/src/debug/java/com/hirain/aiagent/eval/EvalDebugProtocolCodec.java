package com.hirain.aiagent.eval;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hirain.aiagent.VirtualStateMachine.VehicleStatePatch;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 严格解析内部协议，所有格式错误都转为稳定的 errorCode 而不是 Binder 异常。 */
public final class EvalDebugProtocolCodec {
    public static final int MAX_REQUEST_BYTES = 128 * 1024;
    private static final Gson GSON = new Gson();
    private static final Set<String> REQUEST_FIELDS = new HashSet<>(Arrays.asList(
            "protocolVersion", "operation", "correlationId", "leaseToken", "ttlMs", "statePatch"));
    private static final Set<String> VERSION_FIELDS = new HashSet<>(Arrays.asList("major", "minor", "schemaHash"));

    public DecodeResult decode(String json) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_BYTES) return DecodeResult.error("PAYLOAD_TOO_LARGE");
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            rejectUnknown(root, REQUEST_FIELDS);
            if (!root.has("protocolVersion") || !root.get("protocolVersion").isJsonObject()) return DecodeResult.error("INVALID_REQUEST");
            JsonObject version = root.getAsJsonObject("protocolVersion");
            rejectUnknown(version, VERSION_FIELDS);
            if (!version.has("major") || version.get("major").getAsInt() != 1) return DecodeResult.error("PROTOCOL_VERSION_UNSUPPORTED");
            if (!root.has("operation") || !root.get("operation").isJsonPrimitive()) return DecodeResult.error("INVALID_REQUEST");
            EvalDebugRequest request = new EvalDebugRequest();
            request.protocolMajor = version.get("major").getAsInt();
            request.protocolMinor = version.has("minor") ? version.get("minor").getAsInt() : 0;
            request.schemaHash = version.has("schemaHash") ? version.get("schemaHash").getAsString() : null;
            request.operation = root.get("operation").getAsString();
            if (!isOperation(request.operation)) return DecodeResult.error("UNKNOWN_OPERATION");
            request.correlationId = optionalString(root, "correlationId", 256);
            request.leaseToken = optionalString(root, "leaseToken", 512);
            if (root.has("ttlMs")) request.ttlMs = root.get("ttlMs").getAsLong();
            if (root.has("statePatch")) request.statePatch = new VehicleStatePatch(readSystems(root.get("statePatch")));
            return DecodeResult.success(request);
        } catch (Exception ignored) {
            return DecodeResult.error("INVALID_REQUEST");
        }
    }

    public String encode(EvalDebugResponse response) { return GSON.toJson(response); }
    private static boolean isOperation(String operation) {
        return "ACQUIRE_ENVIRONMENT".equals(operation) || "RESET_STATE".equals(operation)
                || "APPLY_STATE".equals(operation) || "READ_STATE".equals(operation)
                || "RELEASE_ENVIRONMENT".equals(operation) || "GET_VERSION".equals(operation);
    }
    private static String optionalString(JsonObject root, String name, int maxLength) {
        if (!root.has(name) || root.get(name).isJsonNull()) return null;
        String value = root.get(name).getAsString();
        if (value.length() > maxLength) throw new IllegalArgumentException();
        return value;
    }
    private static void rejectUnknown(JsonObject object, Set<String> allowed) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!allowed.contains(entry.getKey())) throw new IllegalArgumentException();
        }
    }
    private static Map<String, Map<String, Object>> readSystems(JsonElement patch) {
        if (!patch.isJsonObject()) throw new IllegalArgumentException();
        JsonObject root = patch.getAsJsonObject();
        JsonObject systems = root.has("systems") ? root.getAsJsonObject("systems") : root;
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> system : systems.entrySet()) {
            if (!system.getValue().isJsonObject()) throw new IllegalArgumentException();
            Map<String, Object> fields = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> field : system.getValue().getAsJsonObject().entrySet()) {
                JsonElement value = field.getValue();
                if (!value.isJsonPrimitive()) throw new IllegalArgumentException();
                if (value.getAsJsonPrimitive().isBoolean()) fields.put(field.getKey(), value.getAsBoolean());
                else if (value.getAsJsonPrimitive().isNumber()) fields.put(field.getKey(), value.getAsDouble());
                else if (value.getAsJsonPrimitive().isString()) fields.put(field.getKey(), value.getAsString());
                else throw new IllegalArgumentException();
            }
            result.put(system.getKey(), fields);
        }
        return result;
    }
    public static final class DecodeResult {
        public final EvalDebugRequest request; public final String errorCode;
        private DecodeResult(EvalDebugRequest request, String errorCode) { this.request = request; this.errorCode = errorCode; }
        static DecodeResult success(EvalDebugRequest request) { return new DecodeResult(request, null); }
        static DecodeResult error(String code) { return new DecodeResult(null, code); }
        public boolean isSuccess() { return request != null; }
    }
}
