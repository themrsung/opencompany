package com.coreintra.runtime.webhook;

import com.coreintra.compat.Texts;

import java.io.Serializable;

/**
 * Something worth telling a client system about (§10).
 *
 * <p>The payload arrives already serialised. This module has no opinion about
 * the shape of another module's document or approval, and a parser here would
 * become a schema everyone has to satisfy.
 *
 * <p>The id is the event, not the delivery: two subscribers to the same
 * approval get one id in two rows, so a client that also talks to another
 * client's integration can tell that they saw the same thing.
 */
public final class WebhookEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final String type;
    private final String payload;

    public WebhookEvent(String id, String type, String payload) {
        if (Texts.isBlank(id)) {
            throw new IllegalArgumentException("an event needs an id that subscribers can compare");
        }
        if (Texts.isBlank(type)) {
            throw new IllegalArgumentException(
                    "an event needs a type in \"domain.thing\" form, because that is what a "
                            + "subscription ticks");
        }
        if (Texts.isBlank(payload)) {
            throw new IllegalArgumentException("an event with no payload tells a client nothing");
        }
        this.id = id;
        this.type = Texts.strip(type);
        this.payload = payload;
    }

    public String id() {
        return id;
    }

    public String type() {
        return type;
    }

    public String payload() {
        return payload;
    }

    @Override
    public String toString() {
        return type + " (" + id + ")";
    }
}
