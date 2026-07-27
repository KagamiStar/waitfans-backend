package com.waitfans.backend.pojo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HotSearchJsonContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesTheFrontendHotSearchContract() {
        HotSearch hotSearch = new HotSearch("特厨隋坡", 12.0, 2);
        JsonNode json = objectMapper.valueToTree(hotSearch);

        assertEquals("特厨隋坡", json.get("content").asText());
        assertEquals(12.0, json.get("score").asDouble());
        assertEquals(2, json.get("type").asInt());
        assertEquals(3, json.size());
    }
}
