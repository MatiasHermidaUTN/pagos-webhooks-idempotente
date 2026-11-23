package com.pagos.idempotente;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(String paymentId) {
        super("No existe el pago " + paymentId);
    }
}
