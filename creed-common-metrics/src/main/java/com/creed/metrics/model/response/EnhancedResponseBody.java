package com.creed.metrics.model.response;

import com.creed.metrics.model.GenericAccount;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.List;

/**
 * Body with {@code accounts} promoted from an ad-hoc {@link #with} entry to a typed field, so it reads back
 * as {@link GenericAccount}s instead of Maps.
 * <p>
 * {@code callSuper = true}: without it Lombok compares only {@code accounts}, and two bodies with different
 * profiles would be equal.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class EnhancedResponseBody extends DefaultResponseBody {
    private List<GenericAccount> accounts;
}
