package io.github.wxmyyds.discordautotranslate.core;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.Set;

/** Parsed, version-specific Discord hook metadata. */
public final class HookConfiguration {
    private static volatile HookConfiguration active;

    private final int versionCode;
    private final String rawJson;
    private final JSONObject root;

    private HookConfiguration(int versionCode, String rawJson, JSONObject root) {
        this.versionCode = versionCode;
        this.rawJson = rawJson;
        this.root = root;
    }

    public static HookConfiguration parse(String json, int expectedVersionCode)
            throws JSONException {
        JSONObject root = new JSONObject(json);
        if (root.optInt("schemaVersion", -1) != 1) {
            throw new JSONException("unsupported hook config schemaVersion");
        }
        int versionCode = root.optInt("versionCode", -1);
        if (versionCode != expectedVersionCode) {
            throw new JSONException("hook config versionCode mismatch: " + versionCode);
        }
        JSONObject hooks = root.optJSONObject("hooks");
        if (hooks == null) {
            throw new JSONException("hook config is missing hooks");
        }
        validateHookSelectors(hooks);
        validateEmbedInput(hooks);
        validatePollInput(hooks);
        validateNameInput(hooks);
        validateNodeConstructors(root);
        return new HookConfiguration(versionCode, json, root);
    }

    private static void validateHookSelectors(JSONObject hooks) throws JSONException {
        Iterator<String> ids = hooks.keys();
        while (ids.hasNext()) {
            String id = ids.next();
            JSONObject hook = hooks.optJSONObject(id);
            if (hook == null) {
                throw new JSONException("hook entry is not an object: " + id);
            }
            JSONObject target = hook.optJSONObject("target");
            if (!hook.optBoolean("enabled", true) && target == null) {
                continue;
            }
            if (target == null) {
                throw new JSONException("enabled hook has an incomplete target: " + id);
            }
            requireExactSelectorString(target.get("class"), "hook target class: " + id);
            validateMethodSelector(target);
        }
    }

    private static void validateMethodSelector(JSONObject selector) throws JSONException {
        if (selector.has("namePrefix") || selector.has("minParameterCount")
                || selector.has("selection")) {
            throw new JSONException("deprecated hook selector property");
        }
        requireExactSelectorString(selector.get("name"), "hook method name");
        Object count = selector.get("parameterCount");
        if ((!(count instanceof Integer) && !(count instanceof Long))
                || ((Number) count).longValue() < 0
                || ((Number) count).longValue() > Integer.MAX_VALUE) {
            throw new JSONException("hook selector needs an exact nonnegative parameterCount");
        }
        if (!(selector.get("static") instanceof Boolean)) {
            throw new JSONException("hook selector needs a static boolean");
        }
        if (selector.has("returnType")) {
            requireExactSelectorString(selector.get("returnType"), "hook return type");
        }
        if (selector.has("parameterTypes")) {
            JSONObject types = selector.getJSONObject("parameterTypes");
            Iterator<String> keys = types.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                final int index;
                try {
                    index = Integer.parseInt(key);
                } catch (NumberFormatException error) {
                    throw new JSONException("invalid hook parameter index: " + key);
                }
                if (index < 0 || index >= ((Number) count).intValue()
                        || !Integer.toString(index).equals(key)) {
                    throw new JSONException("invalid hook parameter index: " + key);
                }
                String type = requireExactSelectorString(types.get(key), "hook parameter type");
                if ("void".equals(type)) {
                    throw new JSONException("hook parameter type cannot be void");
                }
            }
        }
    }

    private static String requireExactSelectorString(Object value, String description)
            throws JSONException {
        String text = requireMetadataString(value, description);
        if (text.indexOf('*') >= 0) {
            throw new JSONException("hook selector needs an exact " + description);
        }
        return text;
    }

    private static void validateEmbedInput(JSONObject hooks) throws JSONException {
        JSONObject embed = hooks.optJSONObject("embed.bind");
        if (embed == null || !embed.optBoolean("enabled", true)) {
            return;
        }
        int count = embed.getJSONObject("target").getInt("parameterCount");
        String source = embed.getString("inputSource");
        if ("arguments".equals(source)) {
            int model = embed.getInt("modelArgument");
            int id = embed.getInt("messageIdArgument");
            if (model < 0 || model >= count || id < 0 || id >= count || model == id
                    || embed.has("accessoryArgument")) {
                throw new JSONException("invalid Embed direct input arguments");
            }
        } else if ("accessory".equals(source)) {
            int accessory = embed.getInt("accessoryArgument");
            if (accessory < 0 || accessory >= count || embed.has("modelArgument")
                    || embed.has("messageIdArgument")) {
                throw new JSONException("invalid Embed accessory input argument");
            }
        } else {
            throw new JSONException("unsupported Embed input source");
        }
        JSONObject host = embed.getJSONObject("host");
        String hostSource = host.getString("source");
        if ("field".equals(hostSource)) {
            requireMetadataString(host.get("field"), "Embed host field");
        } else if (!"this".equals(hostSource) || host.has("field")) {
            throw new JSONException("unsupported Embed host source");
        }
        if (embed.getJSONObject("target").getBoolean("static")) {
            throw new JSONException("Embed binding needs an instance host");
        }
    }

    private static void validatePollInput(JSONObject hooks) throws JSONException {
        String[] ids = {"poll.bind", "poll.answerBind", "poll.textSet", "poll.accessibilitySet"};
        boolean present = false;
        for (String id : ids) present |= hooks.has(id);
        if (!present) return;
        for (String id : ids) {
            JSONObject spec = hooks.getJSONObject(id);
            if (!spec.getBoolean("enabled") || spec.getJSONObject("target").getBoolean("static")) {
                throw new JSONException("Poll hooks need enabled instance targets");
            }
            int count = spec.getJSONObject("target").getInt("parameterCount");
            if ("poll.bind".equals(id)) {
                validateIndex(spec, "accessoryArgument", count);
                for (String path : new String[]{"messageIdPath", "textPath", "hostPath", "textViewPath", "optionsPath"})
                    validateFieldPath(spec, path);
            } else if ("poll.answerBind".equals(id)) {
                validateIndex(spec, "holderArgument", count);
                validateIndex(spec, "positionArgument", count);
                if (spec.getInt("holderArgument") == spec.getInt("positionArgument"))
                    throw new JSONException("Poll holder and position must be distinct");
                for (String path : new String[]{"itemsPath", "messageIdPath", "answerIdPath",
                        "textPath", "holderBindingPath", "textViewPath", "imageViewPath"})
                    validateFieldPath(spec, path);
                requireMetadataString(spec.get("textBindingClass"), "Poll text binding class");
                requireMetadataString(spec.get("imageBindingClass"), "Poll image binding class");
                if (spec.getString("textBindingClass").equals(spec.getString("imageBindingClass")))
                    throw new JSONException("Poll binding classes must be distinct");
            } else {
                validateIndex(spec, "contentArgument", count);
            }
        }
    }

    private static void validateIndex(JSONObject spec, String key, int count) throws JSONException {
        int index = spec.getInt(key);
        if (index < 0 || index >= count) throw new JSONException("invalid configured argument: " + key);
    }

    private static void validateNameInput(JSONObject hooks) throws JSONException {
        Iterator<String> keys = hooks.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            if (!id.startsWith("name.")) continue;
            JSONObject binding = hooks.getJSONObject(id);
            JSONObject target = binding.getJSONObject("target");
            int count = target.getInt("parameterCount");
            int viewArgument = binding.getInt("viewArgument");
            if (target.getBoolean("static") || !"void".equals(target.getString("returnType"))
                    || target.getJSONObject("parameterTypes").length() != count
                    || viewArgument < -1 || viewArgument >= count) {
                throw new JSONException("Invalid name binding target");
            }
            validateIndex(binding, "modelArgument", count);
            for (String key : new String[]{"viewPath", "modelPath"}) {
                JSONArray path = binding.getJSONArray(key);
                for (int i = 0; i < path.length(); i++) requireMetadataString(path.get(i), key);
            }
            validateFieldPath(binding, "guildIdPath");
            validateFieldPath(binding, "channelIdPath");
            int server = binding.getInt("serverViewId");
            int channel = binding.getInt("channelViewId");
            if (server <= 0 || channel <= 0 || server == channel) {
                throw new JSONException("Invalid name binding resource IDs");
            }
        }
    }

    private static void validateFieldPath(JSONObject spec, String key) throws JSONException {
        JSONArray path = spec.getJSONArray(key);
        if (path.length() == 0) throw new JSONException("empty configured field path: " + key);
        for (int i = 0; i < path.length(); i++) requireMetadataString(path.get(i), key);
    }

    private static void validateNodeConstructors(JSONObject root) throws JSONException {
        if (!root.has("nodeConstructors")) {
            return;
        }
        JSONObject constructors = root.getJSONObject("nodeConstructors");
        JSONObject types = root.optJSONObject("types");
        Iterator<String> names = constructors.keys();
        while (names.hasNext()) {
            String semanticType = requireMetadataString(names.next(),
                    "node constructor semantic type");
            Object mappedType = types == null ? null : types.opt(semanticType);
            boolean mapped = mappedType instanceof String && !((String) mappedType).trim().isEmpty()
                    && mappedType.equals(((String) mappedType).trim());
            JSONObject aliases = root.optJSONObject("typeAliases");
            if (!mapped && aliases != null) {
                Iterator<String> classNames = aliases.keys();
                while (classNames.hasNext()) {
                    String className = classNames.next();
                    if (!className.trim().isEmpty() && className.equals(className.trim())
                            && semanticType.equals(aliases.opt(className))) {
                        mapped = true;
                        break;
                    }
                }
            }
            if (!mapped) {
                throw new JSONException("node constructor has no mapped type: " + semanticType);
            }
            JSONObject specification = constructors.getJSONObject(semanticType);
            JSONArray parameterTypes = specification.getJSONArray("parameterTypes");
            JSONArray arguments = specification.getJSONArray("arguments");
            if (parameterTypes.length() != arguments.length()) {
                throw new JSONException("node constructor argument count mismatch: " + semanticType);
            }
            int children = 0;
            for (int i = 0; i < parameterTypes.length(); i++) {
                String parameterType = requireMetadataString(parameterTypes.get(i),
                        "node constructor parameter type: " + semanticType);
                if (!parameterType.equals(parameterType.trim())
                        || parameterType.indexOf('*') >= 0 || "void".equals(parameterType)) {
                    throw new JSONException("node constructor needs an exact parameter type: "
                            + semanticType);
                }
                JSONObject argument = arguments.getJSONObject(i);
                String kind = requireMetadataString(argument.get("kind"),
                        "node constructor argument kind: " + semanticType);
                switch (kind) {
                    case "children":
                        children++;
                        if (!"java.util.List".equals(parameterType)) {
                            throw new JSONException("node constructor children must be java.util.List: "
                                    + semanticType);
                        }
                        break;
                    case "field":
                        requireMetadataString(argument.get("name"),
                                "node constructor field name: " + semanticType);
                        break;
                    case "integer":
                        Object value = argument.get("value");
                        if (!"int".equals(parameterType)
                                || (!(value instanceof Integer) && !(value instanceof Long))
                                || ((Number) value).longValue() < Integer.MIN_VALUE
                                || ((Number) value).longValue() > Integer.MAX_VALUE) {
                            throw new JSONException("node constructor integer must be an int: "
                                    + semanticType);
                        }
                        break;
                    default:
                        throw new JSONException("unknown node constructor argument kind: " + kind);
                }
            }
            if (children != 1) {
                throw new JSONException("node constructor needs exactly one children argument: "
                        + semanticType);
            }
        }
    }

    private static String requireMetadataString(Object value, String description)
            throws JSONException {
        if (!(value instanceof String) || ((String) value).trim().isEmpty()
                || !value.equals(((String) value).trim())) {
            throw new JSONException("missing or invalid " + description);
        }
        return (String) value;
    }

    public static void activate(HookConfiguration configuration) {
        active = configuration;
    }

    @Nullable
    public static HookConfiguration activeConfiguration() {
        return active;
    }

    /** Returns a semantic type only when the active profile explicitly maps the runtime class. */
    @Nullable
    public static String semanticTypeName(Class<?> runtimeType) {
        HookConfiguration configuration = active;
        JSONObject types = configuration == null ? null : configuration.root.optJSONObject("types");
        if (types != null) {
            Iterator<String> keys = types.keys();
            while (keys.hasNext()) {
                String semanticName = keys.next();
                String configuredClass = types.optString(semanticName, "");
                if (runtimeType.getName().equals(configuredClass)) {
                    return semanticName;
                }
            }
        }
        JSONObject aliases = configuration == null ? null
                : configuration.root.optJSONObject("typeAliases");
        return aliases == null ? null : aliases.optString(runtimeType.getName(), null);
    }

    @Nullable
    public String fieldName(String semanticType, String memberName) {
        JSONObject members = root.optJSONObject("fields");
        JSONObject typeMembers = members == null ? null : members.optJSONObject(semanticType);
        return typeMembers == null ? null : typeMembers.optString(memberName, null);
    }

    @Nullable
    public String accessorName(String semanticType, String accessor) {
        JSONObject accessors = root.optJSONObject("accessors");
        JSONObject typeAccessors = accessors == null ? null : accessors.optJSONObject(semanticType);
        return typeAccessors == null ? null : typeAccessors.optString(accessor, null);
    }

    /** Exact constructor metadata for a semantic node type, or null for the copy strategy. */
    @Nullable
    public JSONObject nodeConstructor(String semanticType) {
        JSONObject constructors = root.optJSONObject("nodeConstructors");
        return constructors == null ? null : constructors.optJSONObject(semanticType);
    }

    @Nullable
    public String typeClass(String semanticName) {
        JSONObject types = root.optJSONObject("types");
        if (types == null) {
            return null;
        }
        String className = types.optString(semanticName, null);
        return className == null || className.isEmpty() ? null : className;
    }

    public int versionCode() {
        return versionCode;
    }

    public String rawJson() {
        return rawJson;
    }

    public boolean isEnabled(String hookId) {
        JSONObject hook = hook(hookId);
        return hook != null && hook.optBoolean("enabled", true);
    }

    public boolean hasAllRequiredTargets(ClassLoader hostClassLoader) {
        JSONArray required = root.optJSONArray("requiredHooks");
        if (required == null) {
            return false;
        }
        for (int i = 0; i < required.length(); i++) {
            String hookId = required.optString(i, "");
            if (hookId.isEmpty() || !isEnabled(hookId)
                    || findMethod(hookId, hostClassLoader) == null) {
                return false;
            }
        }
        return true;
    }

    /** Recover only from this profile's binding target, never a framework/bootstrap class. */
    @Nullable
    public ClassLoader bindingClassLoader(@Nullable Class<?> declaringClass) {
        JSONObject bind = hook("message.bind");
        JSONObject target = bind == null ? null : bind.optJSONObject("target");
        String expected = target == null ? null : target.optString("class", null);
        if (declaringClass == null || !declaringClass.getName().equals(expected)) {
            return null;
        }
        ClassLoader loader = declaringClass.getClassLoader();
        return loader != null && hasAllRequiredTargets(loader) ? loader : null;
    }

    public boolean hasAllRequiredHooks(Set<String> installedHookIds) {
        JSONArray required = root.optJSONArray("requiredHooks");
        if (required == null || required.length() == 0 || installedHookIds == null) {
            return false;
        }
        for (int i = 0; i < required.length(); i++) {
            String hookId = required.optString(i, "");
            if (hookId.isEmpty() || !isEnabled(hookId) || !installedHookIds.contains(hookId)) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    public JSONObject iconClickConfiguration() {
        return root.optJSONObject("iconClick");
    }

    @Nullable
    public JSONObject hook(String id) {
        JSONObject hooks = root.optJSONObject("hooks");
        return hooks == null ? null : hooks.optJSONObject(id);
    }

    public Set<String> nameHooks() {
        Set<String> names = new java.util.LinkedHashSet<>();
        JSONObject hooks = root.optJSONObject("hooks");
        if (hooks == null) return names;
        Iterator<String> keys = hooks.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("name.") && isEnabled(key)) names.add(key);
        }
        return names;
    }

    @Nullable
    public String string(String section, String key) {
        JSONObject object = root.optJSONObject(section);
        return object == null ? null : object.optString(key, null);
    }

    /** Finds a declared method using only the selector stored in this version's config. */
    @Nullable
    public Method findMethod(String hookId, ClassLoader hostClassLoader) {
        JSONObject hook = hook(hookId);
        JSONObject target = hook == null ? null : hook.optJSONObject("target");
        if (target == null) {
            return null;
        }
        String className = target.optString("class", "");
        if (className.isEmpty()) {
            return null;
        }
        Class<?> owner = Reflect.loadClass(hostClassLoader, className);
        return owner == null ? null : findMethod(owner, target);
    }

    /** Returns the unique declared match, or null for an invalid or ambiguous selector. */
    @Nullable
    public static Method findMethod(Class<?> owner, JSONObject selector) {
        try {
            validateMethodSelector(selector);
        } catch (JSONException invalidSelector) {
            return null;
        }
        String exactName = selector.optString("name");
        boolean requireStatic = selector.optBoolean("static");
        int exactCount = selector.optInt("parameterCount");
        String returnType = selector.optString("returnType", null);
        JSONObject parameterTypes = selector.optJSONObject("parameterTypes");

        Method match = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (!exactName.equals(method.getName())
                    || Modifier.isStatic(method.getModifiers()) != requireStatic) {
                continue;
            }
            Class<?>[] actualTypes = method.getParameterTypes();
            if (actualTypes.length != exactCount) {
                continue;
            }
            if (returnType != null && !returnType.equals(typeName(method.getReturnType()))) {
                continue;
            }
            if (!matchesParameterTypes(actualTypes, parameterTypes)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = method;
        }
        if (match != null) {
            match.setAccessible(true);
        }
        return match;
    }

    private static boolean matchesParameterTypes(Class<?>[] actualTypes,
                                                  @Nullable JSONObject expectedTypes) {
        if (expectedTypes == null) {
            return true;
        }
        Iterator<String> keys = expectedTypes.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            int index = Integer.parseInt(key);
            if (!typeName(actualTypes[index]).equals(expectedTypes.optString(key, null))) {
                return false;
            }
        }
        return true;
    }

    private static String typeName(Class<?> type) {
        if (type.isArray()) {
            return typeName(type.getComponentType()) + "[]";
        }
        return type.getName();
    }
}
