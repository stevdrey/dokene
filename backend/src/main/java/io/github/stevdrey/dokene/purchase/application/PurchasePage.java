package io.github.stevdrey.dokene.purchase.application;

import io.github.stevdrey.dokene.purchase.domain.Purchase;
import java.util.List;

public record PurchasePage(List<Purchase> purchases, String nextCursor) { }
