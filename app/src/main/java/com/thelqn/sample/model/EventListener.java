package com.thelqn.sample.model;

import java.util.List;

public interface EventListener {
    void processMessageInfo(List<Message> messages);
}
