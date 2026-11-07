package com.creed.parent.convert;

import com.fasterxml.jackson.annotation.JsonValue;

public interface PersistEnum2DB<DB> {
    @JsonValue
    DB getData();
}
