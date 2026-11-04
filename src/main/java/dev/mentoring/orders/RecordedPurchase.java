package dev.mentoring.orders;

record RecordedPurchase(long id, long productId, int quantity, PurchaseOutcome outcome) {
    boolean matches(CreateOrderRequest input) {
        return productId == input.productId() && quantity == input.quantity();
    }
}
