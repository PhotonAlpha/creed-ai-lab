package com.acme.shop.invoice;

public class InvoiceService {

    public String generate(String orderId, long amount) {
        StringBuilder invoice = new StringBuilder();
        invoice.append("INVOICE ").append(orderId).append('\n');
        invoice.append("amount: ").append(amount).append('\n');
        invoice.append("status: open");
        return invoice.toString();
    }
}
