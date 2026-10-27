package org.gms.soloacceptance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ObservationRequestTest {
    @Test
    void restLimitRejectsJacksonNumericCoercionsRatherThanChangingTheirMeaning() {
        ObjectMapper json = new ObjectMapper();
        for (String limit : new String[]{"1.9", "\"2\"", "true", "2147483648"}) {
            assertThrows(Exception.class, () -> json.readValue(
                    "{\"scope\":\"world\",\"limit\":" + limit + "}", ObservationRequest.class), limit);
        }
    }

    @Test
    void explicitIntegersAndOptionalNullLimitPreserveTheWireContract() throws Exception {
        ObjectMapper json = new ObjectMapper();
        ObservationRequest scene = json.readValue(
                "{\"scope\":\"scene\",\"scene_ref\":\"scene-ref\",\"limit\":2}", ObservationRequest.class);
        assertEquals("scene-ref", scene.sceneRef());
        assertEquals(2, scene.limit());
        assertNull(json.readValue("{\"scope\":\"world\",\"limit\":null}", ObservationRequest.class).limit());
    }
}
