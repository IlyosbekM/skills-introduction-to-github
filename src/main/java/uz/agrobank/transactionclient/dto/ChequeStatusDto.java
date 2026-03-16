package uz.agrobank.transactionclient.dto;

/**
 * Carries the aggregated BM response counts for a single cheque.
 *
 * @param chequeId    the cheque identifier from BM_CHEQUE_LIST
 * @param bmRequestId the BM request identifier
 * @param accepted    count of accepted responses (MAIL_TYPE = '7050211')
 * @param rejected    count of rejected responses (MAIL_TYPE = '7050213')
 * @param paid        count of paid responses     (MAIL_TYPE = '7050212')
 */
public record ChequeStatusDto(
        String chequeId,
        String bmRequestId,
        int accepted,
        int rejected,
        int paid
) {
}
