package io.github.stevdrey.dokene.purchase.application;

public class PurchaseNotFoundException extends RuntimeException {
    public PurchaseNotFoundException() { super("Purchase is unavailable"); }
}
