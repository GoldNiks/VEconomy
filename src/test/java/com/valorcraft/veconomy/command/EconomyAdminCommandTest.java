package com.valorcraft.veconomy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EconomyAdminCommandTest {

    @Test
    void safeMetadataRedactsSensitiveKeysAndKeepsOthers() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("api_token", "sec-token");
        metadata.put("secret_key", "sec-key");
        metadata.put("user_password_hash", "hunter2");
        metadata.put("ip_address", "10.0.0.7");
        metadata.put("address", "Москва");
        metadata.put("authorization", "Bearer xyz");
        metadata.put("session_id", "abc-123");
        metadata.put("note", "обычное значение");
        metadata.put("Amount", "42");

        Map<String, String> safe = EconomyAdminCommand.safeMetadata(metadata);

        assertEquals(metadata.size(), safe.size(), "ключи сохраняются — меняются только значения");
        for (String key : new String[] {
                "api_token", "secret_key", "user_password_hash",
                "ip_address", "address", "authorization", "session_id",
        }) {
            assertNotEquals(metadata.get(key), safe.get(key),
                    "чувствительное значение «" + key + "» не выводится в открытом виде");
        }
        assertEquals("обычное значение", safe.get("note"), "обычные ключи не трогаются");
        assertEquals("42", safe.get("Amount"), "регистр ключа значения не имеет для маски");
    }
}