package com.thelqn.sqlite3.model;

import java.util.List;

public interface EventListener {
    void processMessageInfo(List<Message> messages);
}
