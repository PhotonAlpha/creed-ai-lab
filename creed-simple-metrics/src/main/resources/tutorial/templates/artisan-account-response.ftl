<#--
  ============================================================================
   artisan-account-response.ftl  -  inbound transformation for one account
  ============================================================================
   Shared by API-7 /accounts/create and /accounts/detail: both methods return a
   single MyAccountDetailsVO, which the component normalises into a Map before
   rendering, so `body` below is that account.
  ============================================================================
-->
{
  "code": "OK",
  "orderId": "${headers.orderId!"N/A"}",
  "account": { "id": "${body.userId}", "owner": "${body.userName!""}", "channel": "${body.channel!""}", "amount": ${(body.amount!0)?c} }
}
