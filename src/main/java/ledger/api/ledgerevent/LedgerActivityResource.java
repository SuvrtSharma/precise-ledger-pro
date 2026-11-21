package ledger.api.ledgerevent;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import ledger.common.LedgerActivityFactory;
import ledger.model.GeneralLedgerActivity;
import ledger.model.LedgerClock;
import ledger.model.LedgerEntry;
import ledger.service.LedgerService;
import ledger.service.LoanService;

import java.util.List;
import java.util.UUID;

import static ledger.util.DateTimeUtil.DB_SAFE_LOCAL_DATETIME_MIN;

@Path("/api/v1/loans/{loanId}/ledger-activities")
@Produces("application/json")
public class LedgerActivityResource {
    @Inject
    LoanService loanService;
    @Inject
    LedgerActivityFactory ledgerActivityFactory;
    @Inject
    LedgerService ledgerService;

    @POST
    @Transactional
    @Consumes(MediaType.APPLICATION_JSON)
    public GeneralLedgerActivity reportLedgerActivity(@PathParam("loanId") UUID loanId,
                                                      GeneralLedgerActivity generalLedgerActivity) {
        generalLedgerActivity.setLoanId(loanId.toString());
        generalLedgerActivity.persist();
        var temporalContext = loanService.getTemporalActivityContext(loanId);
        // Rebuild the whole ledger from every activity on the loan, so a backdated activity or reversal lands where
        // on-time processing would have put it (LedgerService.applyLedgerActivities). The ledger is reconstructed, so
        // its old entries are replaced.
        var activities = GeneralLedgerActivity.<GeneralLedgerActivity>list("loanId", loanId.toString()).stream()
                .map(ledgerActivityFactory::create).toList();
        var ledger = ledgerService.getLedger(loanId, DB_SAFE_LOCAL_DATETIME_MIN);
        ledgerService.applyLedgerActivities(ledger, activities, new LedgerClock(DB_SAFE_LOCAL_DATETIME_MIN),
                temporalContext);
        LedgerEntry.delete("loanId", loanId.toString());
        ledger.getEntries().forEach(ledgerEntry -> ledgerEntry.persist());
        return generalLedgerActivity;
    }

    @GET
    public List<GeneralLedgerActivity> getLedgerActivity(@PathParam("loanId") UUID loanId) {
        return GeneralLedgerActivity.list("loanId", loanId.toString());
    }
}
