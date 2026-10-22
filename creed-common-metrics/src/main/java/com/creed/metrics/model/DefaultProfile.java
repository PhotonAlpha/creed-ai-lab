package com.creed.metrics.model;

/**
 * {@link GenericProfile} pinned to {@link GenericCustomInformation}: a name for the common case, so
 * subclasses and signatures don't repeat the type argument.
 */
public class DefaultProfile extends GenericProfile<GenericCustomInformation> {

    public DefaultProfile() {
    }

    public DefaultProfile(GenericCustomInformation customerInfo) {
        setCustomerInfo(customerInfo);
    }
}
