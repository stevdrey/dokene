package io.github.stevdrey.dokene.purchase.application;

public class PurchaseConflictException extends RuntimeException {
    public PurchaseConflictException() { super("Purchase state conflicts with this request"); }
}
