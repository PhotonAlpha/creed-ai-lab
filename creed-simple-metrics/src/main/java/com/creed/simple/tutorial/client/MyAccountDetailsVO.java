package com.creed.simple.tutorial.client;

/**
 * The account type the external "artisan" service replies with.
 *
 * <p>Only the replies are typed: requests to {@link ArtisanClient} are JSON strings rendered by the
 * request templates, so their shape is defined in the {@code .ftl} files, not here.
 *
 * <p>A record is fine here because the Freemarker templates never touch it directly - the
 * {@code httpexchange:} component normalises the reply into plain Maps first (records have no
 * JavaBean getters, so a template would otherwise not see the fields).
 */
public record MyAccountDetailsVO(String userId,
                                 String userName,
                                 String channel,
                                 Double amount) {
}
