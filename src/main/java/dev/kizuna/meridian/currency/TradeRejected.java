package dev.kizuna.meridian.currency;

public final class TradeRejected extends RuntimeException {
    public enum Reason { FUNDS, CHANGE, SPACE, GOODS, STALE, OPERATION_CONFLICT }

    private final Reason reason;

    TradeRejected(Reason reason) {
        super(reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
