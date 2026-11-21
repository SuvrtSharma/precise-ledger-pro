package ledger.api;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import ledger.common.LedgerActivityFactory;
import ledger.common.ledgeractivity.domain.InterestRate;
import ledger.model.GeneralLedgerActivity;
import ledger.model.LedgerClock;
import ledger.model.LedgerEntry;
import ledger.service.LedgerService;
import ledger.service.LoanService;
import ledger.util.CSVUtil;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.UUID;

import static ledger.util.DateTimeUtil.DB_SAFE_LOCAL_DATETIME_MIN;

@Path("/bulk/api/v1")
@Produces("application/json")
public class BulkResource {
    private final CSVUtil<InterestRate> interestRateCSVUtil = new CSVUtil<>();
    private final CSVUtil<GeneralLedgerActivity> generalLedgerActivityCSVUtil = new CSVUtil<>();
    @Inject
    LoanService loanService;
    @Inject
    LedgerActivityFactory ledgerActivityFactory;
    @Inject
    LedgerService ledgerService;

    // Create a multipart form data endpoint that accepts a CSV file and persists the interest rates
    @POST
    @Path("/loans/{loanId}/interest-rates")
    @Consumes("text/csv")
    @Transactional
    public Response createInterestRatesBulk(@PathParam("loanId") String loanId, InputStream fileInputStream) {
        var reader = new BufferedReader(new InputStreamReader(fileInputStream));
        var interestRates = interestRateCSVUtil.parse(reader, InterestRate.class);

        interestRates.forEach(interestRate -> {
            interestRate.setLoanId(loanId);
            interestRate.persist();
        });

        return Response.status(Response.Status.CREATED).entity(interestRates).build();
    }

    @POST
    @Path("/loans/{loanId}/ledger-activities")
    @Consumes("text/csv")
    @Transactional
    public Response reportLedgerActivityBulk(@PathParam("loanId") UUID loanId, InputStream fileInputStream) {
        var reader = new BufferedReader(new InputStreamReader(fileInputStream));
        var generalLedgerActivities = generalLedgerActivityCSVUtil.parse(reader, GeneralLedgerActivity.class);

        var temporalContext = loanService.getTemporalActivityContext(loanId);
        generalLedgerActivities.forEach(generalLedgerActivity -> {
            generalLedgerActivity.setLoanId(loanId.toString());
            generalLedgerActivity.persist();
        });
        // Rebuild the whole ledger from every activity on the loan, replacing its entries.
        var activities = GeneralLedgerActivity.<GeneralLedgerActivity>list("loanId", loanId.toString()).stream()
                .map(ledgerActivityFactory::create).toList();
        var ledger = ledgerService.getLedger(loanId, DB_SAFE_LOCAL_DATETIME_MIN);
        ledgerService.applyLedgerActivities(ledger, activities, new LedgerClock(DB_SAFE_LOCAL_DATETIME_MIN),
                temporalContext);
        LedgerEntry.delete("loanId", loanId.toString());
        ledger.getEntries().forEach(ledgerEntry -> ledgerEntry.persist());

        return Response.status(Response.Status.CREATED).entity(generalLedgerActivities).build();
    }
}
