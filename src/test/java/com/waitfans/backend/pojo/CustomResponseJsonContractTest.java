package com.waitfans.backend.pojo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomResponseJsonContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesTheSharedFrontendResponseContract() {
        CustomResponse success = new CustomResponse();
        JsonNode successJson = objectMapper.valueToTree(success);

        assertEquals(200, successJson.get("code").asInt());
        assertEquals("OK", successJson.get("message").asText());
        assertTrue(successJson.get("data").isNull());

        CustomResponse rejected = new CustomResponse(403, "账号或密码不正确", null);
        JsonNode rejectedJson = objectMapper.valueToTree(rejected);

        assertEquals(403, rejectedJson.get("code").asInt());
        assertEquals("账号或密码不正确", rejectedJson.get("message").asText());
        assertTrue(rejectedJson.get("data").isNull());
    }
}
