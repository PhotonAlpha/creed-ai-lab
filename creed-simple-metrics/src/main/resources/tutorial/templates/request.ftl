<#--
  ============================================================================
   request.ftl  -  outbound transformation for API-3
  ============================================================================
   Turns THIS application's internal order payload into the shape the external
   FX service expects. Rendered by the freemarker: endpoint before the HTTP call:

       producerTemplate.requestBodyAndHeader(
               "freemarker:tutorial/templates/request.ftl", request, "msgId", msgId, String.class)

   Model variables provided by camel-freemarker:
     body      the In message body (here: the internal order Map)
     headers   the In message headers (here: msgId)
     exchange  the Exchange itself
   The rendered text becomes the new body, so this file is the only place that
   knows the remote system's request format.
  ============================================================================
-->
{
  "header": {
    "msgId": "${headers.msgId}",
    "source": "camel-session-0to1",
    "version": "1.0"
  },
  "payload": {
    "ccy": "${body.currency!"USD"}",
    "amt": ${(body.amount!0)?c},
    "ref": "${body.orderId!"N/A"}"
  }
}
