package com.creed.metrics.model.response;

import com.creed.metrics.model.DefaultProfile;

/**
 * {@link GenericResponseBody} pinned to {@link DefaultProfile}. Service bodies extend this, so a new body
 * is just {@code class XxxResponseBody extends DefaultResponseBody} plus its own fields.
 */
public class DefaultResponseBody extends GenericResponseBody<DefaultProfile> {

    public DefaultResponseBody() {
    }

    public DefaultResponseBody(DefaultProfile profile) {
        setProfile(profile);
    }

    // Covariant return: the inherited with(...) returns GenericResponseBody<DefaultProfile>, which
    // DefaultResponse's constructor would not accept, so a with(...) chain could not be passed straight in.
    @Override
    public DefaultResponseBody with(String name, Object value) {
        super.with(name, value);
        return this;
    }
}
