<#--
  ============================================================================
   artisan-detail-request.ftl  -  outbound transformation for API-7 /accounts/detail
  ============================================================================
   The artisan service looks an account up by "userId"; this application calls
   the same thing accountId. The rendered text is passed to
   ArtisanClient#getUser(@RequestBody String) verbatim.
  ============================================================================
-->
{ "userId": "${body.accountId!"N/A"}" }
