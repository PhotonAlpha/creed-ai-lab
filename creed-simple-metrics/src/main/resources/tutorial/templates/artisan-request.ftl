<#--
  ============================================================================
   artisan-request.ftl  -  outbound transformation for API-7 /accounts and /accounts/create
  ============================================================================
   Renders the internal order into the account shape the artisan service
   expects. The httpexchange: component renders this BEFORE the call and passes
   the text verbatim to the @RequestBody String parameter, so this file is the
   only description of the outbound JSON.

   Model variables are the same as for the freemarker: component
   (body / headers / exchange), so this file would render identically either way.
  ============================================================================
-->
{
  "userId":   "${body.orderId!"N/A"}",
  "userName": "${headers.userName!"anonymous"}",
  "channel":  "${body.channel!"WEB"}",
  "amount":   ${(body.amount!0)?c}
}
