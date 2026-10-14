<#--
  ============================================================================
   artisan-response.ftl  -  inbound transformation for API-7
  ============================================================================
   The client method returns ResponseEntity<List<MyAccountDetailsVO>>. The
   component unwraps the ResponseEntity and normalises the list into plain Maps,
   so `body` below is a list of maps and no getter is required.

   Whatever this renders becomes the message body; with unmarshalTo=java.util.Map
   the component parses it back so the REST layer can serialise it as JSON.

   Note: FreeMarker has no ?sum builtin, so totals are accumulated with <#assign>.
  ============================================================================
-->
<#assign total = 0>
<#list body as a><#assign total = total + a.amount></#list>
{
  "code": "OK",
  "orderId": "${headers.orderId!"N/A"}",
  "accountCount": ${body?size},
  "totalAmount": ${total?c},
  "accounts": [
<#list body as a>
    { "id": "${a.userId}", "owner": "${a.userName}", "channel": "${a.channel}", "amount": ${a.amount?c} }<#if a?has_next>,</#if>
</#list>
  ]
}
