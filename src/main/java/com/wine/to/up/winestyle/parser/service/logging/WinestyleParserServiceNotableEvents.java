package com.wine.to.up.winestyle.parser.service.logging;

import com.wine.to.up.commonlib.logging.NotableEvent;

public enum WinestyleParserServiceNotableEvents implements NotableEvent {
    I_KAFKA_SEND_MESSAGE_SUCCESS("Kafka send message: {}"),
    I_CONTROLLER_RECEIVED_MESSAGE("Message: {}"),
    W_SOME_WARN_EVENT("Warn situation. Description: {}");
    //TODO create-service: replace
    private final String template;

    WinestyleParserServiceNotableEvents(String template) {
        this.template = template;
    }

    public String getTemplate() {
        return template;
    }

    @Override
    public String getName() {
        return name();
    }


}