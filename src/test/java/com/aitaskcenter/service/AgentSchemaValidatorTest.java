package com.aitaskcenter.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AgentSchemaValidatorTest {
    private final AgentSchemaValidator validator = new AgentSchemaValidator(new ObjectMapper());

    @Test
    void reportsMissingRequiredField() {
        var value = validator.parseJson("{\"name\":\"demo\"}", "输入");
        var schema = validator.parseJson("{\"type\":\"object\",\"required\":[\"target_words\"]}", "Schema");

        assertThat(validator.validate(value, schema)).containsExactly("$.target_words 为必填字段");
    }

    @Test
    void validatesNestedArrayItems() {
        var value = validator.parseJson("{\"target_words\":[{\"id\":1}]}", "输入");
        var schema = validator.parseJson("""
                {"type":"object","required":["target_words"],"properties":{"target_words":{"type":"array","items":{"type":"object","required":["id"]}}}}
                """, "Schema");

        assertThat(validator.validate(value, schema)).isEmpty();
    }
}
