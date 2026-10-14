<#--
  ============================================================================
   response.ftl  -  inbound transformation for API-3
  ============================================================================
   Turns the external FX service's reply back into the flat shape the rest of
   this application works with. Rendered after the HTTP call:

       producerTemplate.requestBody(
               "freemarker:tutorial/templates/response.ftl", remoteReplyMap, String.class)

   The remote reply arrives as JSON text, is parsed into a Map, and that Map is
   the `body` below. The rendered text is parsed back into the business Map, so
   nothing downstream ever sees the remote system's nested envelope.
  ============================================================================
-->
{
  "currency": "${body.payload.ccy}",
  "rate": ${body.payload.fxRate?c},
  "provider": "${body.payload.provider}",
  "remoteMsgId": "${body.header.msgId}",
  "remoteCode": "${body.header.code}"
}
