package com.pagos.idempotente;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WebhookResponse(
        @JsonProperty("event_id") String eventId,
        WebhookOutcome outcome) {
}
