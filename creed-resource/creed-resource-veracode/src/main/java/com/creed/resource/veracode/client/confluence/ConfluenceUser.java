package com.creed.resource.veracode.client.confluence;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * @param type     {@code known} for a signed-in user, {@code anonymous} for a call Confluence took without one
 * @param username Data Center's login; Cloud has {@code accountId} and {@code email} instead
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfluenceUser(String type, String username, String accountId, String email, String displayName) {

    public boolean anonymous() {
        return "anonymous".equalsIgnoreCase(type);
    }

    public String name() {
        return displayName != null ? displayName : username != null ? username : email != null ? email : accountId;
    }
}
