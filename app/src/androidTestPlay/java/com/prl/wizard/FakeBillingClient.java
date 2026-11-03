package com.prl.wizard;

/** A controlled Play client: no purchase sheet, payment or Play account is used by these checks. */
public class FakeBillingClient extends com.android.billingclient.api.BillingClient {
    public boolean ready, holdDetails;
    public int launches;
    public com.android.billingclient.api.BillingClientStateListener connection;
    public com.android.billingclient.api.ProductDetailsResponseListener productListener;
    public java.util.List<com.android.billingclient.api.Purchase> purchases = java.util.Collections.emptyList();

    public static com.android.billingclient.api.BillingResult ok() {
        return com.android.billingclient.api.BillingResult.newBuilder().setResponseCode(0).build();
    }

    public void disconnect() {
        ready = false;
        connection.onBillingServiceDisconnected();
    }

    public void replyProduct() {
        try {
            java.lang.reflect.Constructor<com.android.billingclient.api.ProductDetails> constructor =
                com.android.billingclient.api.ProductDetails.class.getDeclaredConstructor(String.class);
            constructor.setAccessible(true);
            com.android.billingclient.api.ProductDetails product = constructor.newInstance("""
                {"productId":"com.prl.wizard.pro","type":"inapp","oneTimePurchaseOfferDetails":
                 {"formattedPrice":"$1.00","priceAmountMicros":1000000,"priceCurrencyCode":"USD","offerIdToken":"test-offer"}}
                """);
            productListener.onProductDetailsResponse(ok(), com.android.billingclient.api.QueryProductDetailsResult.create(
                java.util.Collections.singletonList(product), java.util.Collections.emptyList()));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Override public int getConnectionState() { return ready ? 2 : 0; }
    @Override public com.android.billingclient.api.BillingResult isFeatureSupported(java.lang.String a0) { throw new UnsupportedOperationException("Unexpected isFeatureSupported"); }
    @Override public com.android.billingclient.api.BillingResult launchBillingFlow(android.app.Activity a0, com.android.billingclient.api.BillingFlowParams a1) { launches++; return ok(); }
    @Override public com.android.billingclient.api.BillingResult showAlternativeBillingOnlyInformationDialog(android.app.Activity a0, com.android.billingclient.api.AlternativeBillingOnlyInformationDialogListener a1) { throw new UnsupportedOperationException("Unexpected showAlternativeBillingOnlyInformationDialog"); }
    @Override public com.android.billingclient.api.BillingResult showExternalOfferInformationDialog(android.app.Activity a0, com.android.billingclient.api.ExternalOfferInformationDialogListener a1) { throw new UnsupportedOperationException("Unexpected showExternalOfferInformationDialog"); }
    @Override public com.android.billingclient.api.BillingResult showInAppMessages(android.app.Activity a0, com.android.billingclient.api.InAppMessageParams a1, com.android.billingclient.api.InAppMessageResponseListener a2) { throw new UnsupportedOperationException("Unexpected showInAppMessages"); }
    @Override public void acknowledgePurchase(com.android.billingclient.api.AcknowledgePurchaseParams a0, com.android.billingclient.api.AcknowledgePurchaseResponseListener a1) { throw new UnsupportedOperationException("Unexpected acknowledgePurchase"); }
    @Override public void consumeAsync(com.android.billingclient.api.ConsumeParams a0, com.android.billingclient.api.ConsumeResponseListener a1) { throw new UnsupportedOperationException("Unexpected consumeAsync"); }
    @Override public void createAlternativeBillingOnlyReportingDetailsAsync(com.android.billingclient.api.AlternativeBillingOnlyReportingDetailsListener a0) { throw new UnsupportedOperationException("Unexpected createAlternativeBillingOnlyReportingDetailsAsync"); }
    @Override public void createBillingProgramReportingDetailsAsync(com.android.billingclient.api.BillingProgramReportingDetailsParams a0, com.android.billingclient.api.BillingProgramReportingDetailsListener a1) { throw new UnsupportedOperationException("Unexpected createBillingProgramReportingDetailsAsync"); }
    @Override public void createExternalOfferReportingDetailsAsync(com.android.billingclient.api.ExternalOfferReportingDetailsListener a0) { throw new UnsupportedOperationException("Unexpected createExternalOfferReportingDetailsAsync"); }
    @Override public void endConnection() { throw new UnsupportedOperationException("Unexpected endConnection"); }
    @Override public void getBillingChoiceInfoAsync(com.android.billingclient.api.GetBillingChoiceInfoParams a0, com.android.billingclient.api.BillingChoiceInfoResponseListener a1) { throw new UnsupportedOperationException("Unexpected getBillingChoiceInfoAsync"); }
    @Override public void getBillingConfigAsync(com.android.billingclient.api.GetBillingConfigParams a0, com.android.billingclient.api.BillingConfigResponseListener a1) { throw new UnsupportedOperationException("Unexpected getBillingConfigAsync"); }
    @Override public void isAlternativeBillingOnlyAvailableAsync(com.android.billingclient.api.AlternativeBillingOnlyAvailabilityListener a0) { throw new UnsupportedOperationException("Unexpected isAlternativeBillingOnlyAvailableAsync"); }
    @Override public void isBillingProgramAvailableAsync(int a0, com.android.billingclient.api.BillingProgramAvailabilityListener a1) { throw new UnsupportedOperationException("Unexpected isBillingProgramAvailableAsync"); }
    @Override public void isExternalOfferAvailableAsync(com.android.billingclient.api.ExternalOfferAvailabilityListener a0) { throw new UnsupportedOperationException("Unexpected isExternalOfferAvailableAsync"); }
    @Override public void launchExternalLink(android.app.Activity a0, com.android.billingclient.api.LaunchExternalLinkParams a1, com.android.billingclient.api.LaunchExternalLinkResponseListener a2) { throw new UnsupportedOperationException("Unexpected launchExternalLink"); }
    @Override public void queryProductDetailsAsync(com.android.billingclient.api.QueryProductDetailsParams a0, com.android.billingclient.api.ProductDetailsResponseListener a1) { productListener = a1; if (!holdDetails) replyProduct(); }
    @Override public void queryPurchasesAsync(com.android.billingclient.api.QueryPurchasesParams a0, com.android.billingclient.api.PurchasesResponseListener a1) { a1.onQueryPurchasesResponse(ok(), purchases); }
    @Override public void showBillingProgramInformationDialog(android.app.Activity a0, com.android.billingclient.api.BillingProgramInformationDialogParams a1, com.android.billingclient.api.BillingProgramInformationDialogListener a2) { throw new UnsupportedOperationException("Unexpected showBillingProgramInformationDialog"); }
    @Override public void startConnection(com.android.billingclient.api.BillingClientStateListener a0) { connection = a0; ready = true; a0.onBillingSetupFinished(ok()); }
    @Override public boolean isReady() { return ready; }
}
