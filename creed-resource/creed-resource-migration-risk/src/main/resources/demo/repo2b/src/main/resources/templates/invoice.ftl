<h1>Invoice ${invoice.number}</h1>
<p>Customer: ${invoice.customer}</p>
<table>
<#list invoice.lines as line>
  <tr><td>${line.name}</td><td>${line.amount}</td></tr>
</#list>
</table>
