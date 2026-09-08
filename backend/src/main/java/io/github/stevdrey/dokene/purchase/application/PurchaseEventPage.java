package io.github.stevdrey.dokene.purchase.application;

import io.github.stevdrey.dokene.purchase.domain.PurchaseEvent;
import java.util.List;

public record PurchaseEventPage(List<PurchaseEvent> events, String nextCursor) { }
