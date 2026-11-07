package com.creed.parent.ccrg;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SourceData implements Serializable {

    private String key;

    private Object value;

    private int source;

    private String origin;

    private boolean active;

    private Metadata metadata;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SourceData that = (SourceData) o;
        return new EqualsBuilder().append(key, that.key).append(value, that.value).isEquals();
    }

    @Override
    public int hashCode() {
        return new HashCodeBuilder(17, 37).append(key).append(value).toHashCode();
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Metadata {

        private String status;

        private String previousValue;

    }

}
