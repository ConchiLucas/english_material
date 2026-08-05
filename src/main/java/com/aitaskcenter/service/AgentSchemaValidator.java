package com.aitaskcenter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AgentSchemaValidator {
    private final ObjectMapper objectMapper;

    public AgentSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode parseJson(String value, String label) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        try {
            return objectMapper.readTree(stripMarkdownFence(value));
        } catch (Exception ex) {
            throw new IllegalArgumentException(label + "不是有效 JSON: " + ex.getMessage());
        }
    }

    public String normalizeSchema(String value, String label) {
        JsonNode schema = parseJson(value, label);
        if (!schema.isObject()) {
            throw new IllegalArgumentException(label + "必须是 JSON 对象");
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(schema);
        } catch (Exception ex) {
            throw new IllegalArgumentException(label + "无法序列化");
        }
    }

    public List<String> validate(JsonNode value, JsonNode schema) {
        List<String> errors = new ArrayList<>();
        validateNode(value, schema, "$", errors);
        return errors;
    }

    private void validateNode(JsonNode value, JsonNode schema, String path, List<String> errors) {
        String type = schema.path("type").asText("");
        if (!type.isBlank() && !matchesType(value, type)) {
            errors.add(path + " 应为 " + type);
            return;
        }
        if ("object".equals(type) || schema.has("properties")) {
            for (JsonNode required : schema.path("required")) {
                String field = required.asText();
                if (!value.has(field) || value.get(field).isNull()) {
                    errors.add(path + "." + field + " 为必填字段");
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = schema.path("properties").fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (value.has(field.getKey()) && !value.get(field.getKey()).isNull()) {
                    validateNode(value.get(field.getKey()), field.getValue(), path + "." + field.getKey(), errors);
                }
            }
        }
        if ("array".equals(type) && value.isArray()) {
            int min = schema.path("minItems").asInt(-1);
            int max = schema.path("maxItems").asInt(-1);
            if (min >= 0 && value.size() < min) errors.add(path + " 至少需要 " + min + " 项");
            if (max >= 0 && value.size() > max) errors.add(path + " 最多允许 " + max + " 项");
            JsonNode itemSchema = schema.path("items");
            if (!itemSchema.isMissingNode()) {
                for (int index = 0; index < value.size(); index++) {
                    validateNode(value.get(index), itemSchema, path + "[" + index + "]", errors);
                }
            }
        }
    }

    private boolean matchesType(JsonNode value, String type) {
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "null" -> value.isNull();
            default -> true;
        };
    }

    public static String stripMarkdownFence(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("```")) {
            int firstLine = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                return trimmed.substring(firstLine + 1, lastFence).trim();
            }
        }
        return trimmed;
    }
}
