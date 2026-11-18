package ledger.api.ledgerevent;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import ledger.model.Balance;
import ledger.model.LedgerEntry;
import ledger.service.LedgerService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static ledger.util.DateTimeUtil.DB_SAFE_LOCAL_DATETIME_MIN;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Known failures in retroactive reconciliation, through the single-activity API. Both tests fail on the current
 * code. Each expected value is what the same activities give when posted in business order, where no backdated or
 * reversal path is needed.
 */
@QuarkusTest
public class RetroactiveReconciliationE2ETest {

    @Inject
    LedgerService ledgerService;

    @Test
    public void twoReversalsInARow_leaveNothingOwed() {
        UUID loanId = createLoan();
        postActivity(loanId, disbursal("a", "2024-03-01T12:00:00", "2024-04-01T00:00:00"));
        postActivity(loanId, disbursal("b", "2024-03-02T12:00:00", "2024-04-01T01:00:00"));
        postActivity(loanId, reversal("r1", "b", "2024-03-03T12:00:00", "2024-04-01T02:00:00"));
        postActivity(loanId, reversal("r2", "a", "2024-03-04T12:00:00", "2024-04-01T03:00:00"));

        var balance = ledgerService.getLedger(loanId).getCurrentBalance();
        assertComponents(0.0, 0.0, 0.0, 0.0, balance, "current balance");
    }

    @Test
    public void backdatedPayment_entriesAddUpToTheBalance() {
        // Business order: a (03-01), payment (03-02) leaves 10 excess, b (03-03) uses it, d (03-04) adds 10
        // principal: principal 10, excess 0. The payment arrives after b, so it is backdated.
        UUID loanId = createLoan();
        postActivity(loanId, disbursal("a", "2024-03-01T12:00:00", "2024-04-01T00:00:00"));
        postActivity(loanId, disbursal("b", "2024-03-03T12:00:00", "2024-04-01T01:00:00"));
        postActivity(loanId, activityJson("c", "Payment", "Transaction", "ComputationalSpread", "DEBIT", "FIP",
                null, null, 20.0, "2024-03-02T12:00:00", "2024-04-01T02:00:00"));
        postActivity(loanId, disbursal("d", "2024-03-04T12:00:00", "2024-04-01T03:00:00"));

        var ledger = ledgerService.getLedger(loanId);
        var sumOfEntries = ledger.getEntries().stream().map(LedgerEntry::getBalanceChange)
                .reduce(Balance::add).orElseThrow();
        assertComponents(10.0, 0.0, 0.0, 0.0, sumOfEntries, "sum of all entries");
        assertComponents(10.0, 0.0, 0.0, 0.0, ledger.getCurrentBalance(), "current balance");
    }

    private void assertComponents(double principal, double interest, double fee, double excess, Balance actual,
                                  String what) {
        assertEquals(principal, actual.principal().getNumber().doubleValue(), what + ": principal");
        assertEquals(interest, actual.interest().getNumber().doubleValue(), what + ": interest");
        assertEquals(fee, actual.fee().getNumber().doubleValue(), what + ": fee");
        assertEquals(excess, actual.excess().getNumber().doubleValue(), what + ": excess");
    }

    private String disbursal(String id, String effectiveAt, String transactionTime) {
        return activityJson(id, "Disbursal", "Transaction", "ComputationalSpread", "CREDIT", "P", null, null, 10.0,
                effectiveAt, transactionTime);
    }

    private String reversal(String id, String target, String effectiveAt, String transactionTime) {
        return activityJson(id, "Reversal of " + target, "Reversal", null, null, null, "Transaction", target, 0.0,
                effectiveAt, transactionTime);
    }

    private UUID createLoan() {
        var response = given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "daysInYear", 365,
                        "currencyCode", "USD",
                        "externalId", "",
                        "lastLedgerFreezePrincipalBalance", 0.0,
                        "lastLedgerFreezeInterestBalance", 0.0,
                        "lastLedgerFreezeFeeBalance", 0.0,
                        "lastLedgerFreezeExcessBalance", 0.0,
                        "lastLedgerFrozenOn", DB_SAFE_LOCAL_DATETIME_MIN.toString()
                ))
                .post("/api/v1/loans");
        response.then().statusCode(200);
        return response.body().jsonPath().getUUID("id");
    }

    @SuppressWarnings("unused")
    private void createInterestRate(UUID loanId, String effectiveAt, float rate) {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "loanId", loanId.toString(),
                        "rate", rate,
                        "effectiveAt", effectiveAt
                ))
                .post("/api/v1/interest-rates")
                .then()
                .statusCode(201);
    }

    private void postActivity(UUID loanId, String activityJson) {
        given()
                .contentType(ContentType.JSON)
                .pathParam("loanId", loanId)
                .body(activityJson)
                .post("/api/v1/loans/{loanId}/ledger-activities")
                .then()
                .statusCode(200);
    }

    /**
     * Builds a JSON string for a GeneralLedgerActivity, ensuring all nullable Double fields have defaults.
     */
    private String activityJson(String activityId, String commonName, String activityType,
                                String transactionStrategy, String direction, String spread,
                                String reversalActivityType, String reversalActivityId,
                                double amount, String effectiveAt, String transactionTime) {
        var sb = new StringBuilder("{");
        sb.append("\"activityId\":\"").append(activityId).append("\",");
        sb.append("\"commonName\":\"").append(commonName).append("\",");
        sb.append("\"activityType\":\"").append(activityType).append("\",");
        if (transactionStrategy != null)
            sb.append("\"transactionStrategy\":\"").append(transactionStrategy).append("\",");
        if (direction != null)
            sb.append("\"direction\":\"").append(direction).append("\",");
        if (spread != null)
            sb.append("\"spread\":\"").append(spread).append("\",");
        if (reversalActivityType != null)
            sb.append("\"reversalActivityType\":\"").append(reversalActivityType).append("\",");
        if (reversalActivityId != null)
            sb.append("\"reversalActivityId\":\"").append(reversalActivityId).append("\",");
        sb.append("\"amount\":").append(amount).append(",");
        sb.append("\"principal\":0.0,");
        sb.append("\"interest\":0.0,");
        sb.append("\"fee\":0.0,");
        sb.append("\"excess\":0.0,");
        sb.append("\"effectiveAt\":\"").append(effectiveAt).append("\",");
        sb.append("\"transactionTime\":\"").append(transactionTime).append("\"");
        sb.append("}");
        return sb.toString();
    }
}
