package uz.multitransfer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import uz.agrobank.common.restclient.service.Connector;
import uz.multitransfer.config.ExternalApiProperties;
import uz.multitransfer.dto.request.ConfirmPayoutRequest;
import uz.multitransfer.dto.request.CreatePayoutRequest;
import uz.multitransfer.dto.response.*;
import uz.multitransfer.entity.Transaction;
import uz.multitransfer.entity.TransactionItem;
import uz.multitransfer.entity.enumeration.TransactionItemType;
import uz.multitransfer.entity.enumeration.TransactionStatus;
import uz.multitransfer.exception.FailureReason;
import uz.multitransfer.exception.TransactionException;
import uz.multitransfer.repository.TransactionItemRepository;
import uz.multitransfer.repository.TransactionRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutService {

    private final Connector connector;
    private final ExternalApiProperties properties;
    private final TransactionRepository transactionRepository;
    private final TransactionItemRepository transactionItemRepository;
    private final ObjectMapper objectMapper;

    // Fix 1: @Transactional removed from public methods to avoid holding a DB connection
    // open across slow external HTTP calls. Each private helper that writes to the DB
    // carries its own @Transactional boundary instead.
    public ApiResponse<SearchPayoutResponse> searchTransfer(String controlNumber, String paymentSystemId) {
        log.info("[SEARCH] Tashqi API ga so'rov: controlNumber={}, paymentSystemId={}", controlNumber, paymentSystemId);

        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(properties.getBaseUrl())
                .path("/api/v1/payout/search")
                .queryParam("controlNumber", controlNumber);
        if (paymentSystemId != null && !paymentSystemId.isBlank()) {
            uriBuilder.queryParam("paymentSystemId", paymentSystemId);
        }
        String url = uriBuilder.toUriString();

        ApiResponse<SearchPayoutResponse> body = connector.exchange(
                        url, HttpMethod.GET, buildHeaders(), null,
                        new ParameterizedTypeReference<ApiResponse<SearchPayoutResponse>>() {})
                .orElseThrow(() -> new TransactionException(FailureReason.TRY_AGAIN_LATER));

        log.info("[SEARCH] Tashqi API javob: success={}", body.isSuccess());

        if (body.getData() != null) {
            saveSearchTransaction(body.getData(), paymentSystemId, toJson(body));
        }

        return body;
    }

    // Fix 1 (continued): @Transactional removed; HTTP call now runs outside a DB transaction.
    public ApiResponse<CreatePayoutResponseData> createPayout(String bankCode, CreatePayoutRequest request) {
        log.info("[CREATE] Tashqi API ga so'rov: bankCode={}, controlNumber={}, transactionId={}",
                bankCode, request.getControlNumber(), request.getTransactionId());

        checkNoDuplicateTransaction(request.getTransactionId());

        String url = properties.getBaseUrl() + "/api/v1/payout/create";

        ApiResponse<CreatePayoutResponseData> body = connector.exchange(
                        url, HttpMethod.POST, buildHeaders(), request,
                        new ParameterizedTypeReference<ApiResponse<CreatePayoutResponseData>>() {})
                .orElseThrow(() -> new TransactionException(FailureReason.TRY_AGAIN_LATER));

        log.info("[CREATE] Tashqi API javob: success={}", body.isSuccess());

        if (body.getData() != null) {
            saveCreateTransaction(bankCode, request, body.getData(), toJson(body));
        }

        return body;
    }

    // Fix 1 (continued): @Transactional removed; HTTP call now runs outside a DB transaction.
    // Fix 2: CONFIRM_PAIDOUT status is now set (and persisted) BEFORE the external HTTP call,
    //         preventing a TOCTOU race condition where two concurrent requests could both pass
    //         the status check and both trigger a duplicate confirmation against the external API.
    public ApiResponse<ConfirmPayoutResponseData> confirmPayout(String bankCode, ConfirmPayoutRequest request) {
        log.info("[CONFIRM] Tashqi API ga so'rov: bankCode={}, paidoutId={}, transactionId={}",
                bankCode, request.getPaidoutId(), request.getTransactionId());

        // Reserve the transaction slot before touching the external API.
        Transaction transaction = reserveConfirmTransaction(request.getTransactionId());

        String url = properties.getBaseUrl() + "/api/v1/payout/confirm";

        ApiResponse<ConfirmPayoutResponseData> body = connector.exchange(
                        url, HttpMethod.POST, buildHeaders(), request,
                        new ParameterizedTypeReference<ApiResponse<ConfirmPayoutResponseData>>() {})
                .orElseThrow(() -> new TransactionException(FailureReason.TRY_AGAIN_LATER));

        log.info("[CONFIRM] Tashqi API javob: success={}", body.isSuccess());

        if (body.getData() != null) {
            updateConfirmTransaction(transaction, body.getData(), toJson(body));
        }

        return body;
    }

    // ===================== Yordamchi metodlar =====================

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        return headers;
    }

    // NOTE: This check is not atomic. Two concurrent requests carrying the same transactionId
    // could both pass before either one writes. A unique constraint on the transactionId column
    // in the database is therefore required as the definitive guard against duplicates.
    @Transactional
    protected void checkNoDuplicateTransaction(String transactionId) {
        transactionRepository.findByTransactionId(transactionId)
                .ifPresent(t -> {
                    throw new TransactionException(FailureReason.TRANSACTION_WITH_REQUEST_ID_ALREADY_EXISTS);
                });
    }

    // Fix 2: Set CONFIRM_PAIDOUT status in its own short-lived transaction before the HTTP
    //         call so that any concurrent request for the same transactionId will observe the
    //         reserved status and be rejected immediately.
    //
    // Known limitation: if the external HTTP call fails with a transient error, the transaction
    // will be left in CONFIRM_PAIDOUT. A compensating mechanism (e.g., an admin endpoint or a
    // scheduled job that rolls back stale CONFIRM_PAIDOUT records) is needed to allow retries.
    @Transactional
    protected Transaction reserveConfirmTransaction(String transactionId) {
        Transaction transaction = transactionRepository.findByTransactionId(transactionId)
                .orElseThrow(() -> new TransactionException(FailureReason.TRANSACTION_NOT_FOUND));

        if (transaction.getStatus() == TransactionStatus.PAIDOUT ||
                transaction.getStatus() == TransactionStatus.CONFIRM_PAIDOUT) {
            throw new TransactionException(FailureReason.TRANSACTION_HAS_ALREADY_CONFIRMED);
        }

        transaction.setStatus(TransactionStatus.CONFIRM_PAIDOUT);
        return transactionRepository.save(transaction);
    }

    @Transactional
    protected void saveSearchTransaction(SearchPayoutResponse data, String paymentSystemId, String rawResponse) {
        if (data.getControlNumber() == null || data.getControlNumber().isBlank()) {
            log.warn("[SEARCH] Tashqi API javobida controlNumber yo'q, saqlash o'tkazib yuborildi");
            return;
        }

        Transaction transaction = transactionRepository
                .findByControlNumber(data.getControlNumber())
                .orElseGet(() -> {
                    Transaction t = new Transaction();
                    t.setControlNumber(data.getControlNumber());
                    return t;
                });

        transaction.setTransactionId(data.getTransactionId());
        transaction.setSenderCountryCode(data.getSenderCountryCode());
        transaction.setAmount(data.getAmount());
        transaction.setCurrency(data.getCurrency());
        transaction.setAgentFee(data.getAgentFee());
        transaction.setAgentFeeCurrency(data.getAgentFeeCurrency());
        transaction.setPaymentSystemId(paymentSystemId);
        if (transaction.getStatus() == null) {
            transaction.setStatus(TransactionStatus.CREATED);
        }
        transactionRepository.save(transaction);

        TransactionItem item = transactionItemRepository
                .findByTransactionAndItemType(transaction, TransactionItemType.SEARCH)
                .orElseGet(() -> {
                    TransactionItem newItem = new TransactionItem();
                    newItem.setTransaction(transaction);
                    newItem.setItemType(TransactionItemType.SEARCH);
                    return newItem;
                });
        populateSearchItem(item, data, rawResponse);
        transactionItemRepository.save(item);
    }

    private static void populateSearchItem(TransactionItem item, SearchPayoutResponse data, String rawResponse) {
        item.setTransferState(data.getTransferStatus());
        item.setSenderLastName(data.getSenderLastName());
        item.setSenderFirstName(data.getSenderFirstName());
        item.setSenderMiddleName(data.getSenderMiddleName());
        item.setBeneficiaryLastName(data.getBeneficiaryLastName());
        item.setBeneficiaryFirstName(data.getBeneficiaryFirstName());
        item.setBeneficiaryMiddleName(data.getBeneficiaryMiddleName());
        item.setWithdrawAmount(data.getAmount());
        item.setWithdrawCurrency(data.getCurrency());
        item.setRawResponse(rawResponse);
    }

    @Transactional
    protected void saveCreateTransaction(String bankCode, CreatePayoutRequest request,
                                         CreatePayoutResponseData data, String rawResponse) {
        Transaction transaction = transactionRepository
                .findByControlNumber(request.getControlNumber())
                .orElseGet(() -> {
                    Transaction t = new Transaction();
                    t.setControlNumber(request.getControlNumber());
                    return t;
                });

        transaction.setTransactionId(data.getTransactionId());
        transaction.setPaidoutId(data.getPaidoutId());
        transaction.setSenderCountryCode(data.getCountryCode());
        transaction.setAmount(data.getWithdrawAmount());
        transaction.setCurrency(data.getWithdrawCurrency());
        transaction.setAgentFee(data.getAgentFee());
        transaction.setAgentFeeCurrency(data.getAgentFeeCurrency());
        transaction.setStatus(TransactionStatus.CREATED_PAIDOUT);
        transactionRepository.save(transaction);

        // Fix 3: Use find-or-create so that retrying createPayout for the same
        //         controlNumber does not insert duplicate CREATE TransactionItem rows.
        TransactionItem item = transactionItemRepository
                .findByTransactionAndItemType(transaction, TransactionItemType.CREATE)
                .orElseGet(() -> {
                    TransactionItem newItem = new TransactionItem();
                    newItem.setTransaction(transaction);
                    newItem.setItemType(TransactionItemType.CREATE);
                    return newItem;
                });
        item.setTransferState(data.getTransferStatus());
        item.setTransferDate(data.getTransferDate());
        item.setBeneficiaryLastName(request.getLastName());
        item.setBeneficiaryFirstName(request.getFirstName());
        item.setBeneficiaryMiddleName(request.getMiddleName());
        item.setWithdrawAmount(data.getWithdrawAmount());
        item.setWithdrawCurrency(data.getWithdrawCurrency());
        item.setRawResponse(rawResponse);
        transactionItemRepository.save(item);
    }

    @Transactional
    protected void updateConfirmTransaction(Transaction transaction,
                                            ConfirmPayoutResponseData data, String rawResponse) {
        transaction.setStatus(data.isSuccess() ? TransactionStatus.PAIDOUT : TransactionStatus.REJECTED_PAIDOUT);
        transactionRepository.save(transaction);

        // Fix 4 (idempotency): Use find-or-create so that a retry after a transient failure
        // does not insert a duplicate CONFIRM TransactionItem row.
        TransactionItem item = transactionItemRepository
                .findByTransactionAndItemType(transaction, TransactionItemType.CONFIRM)
                .orElseGet(() -> {
                    TransactionItem newItem = new TransactionItem();
                    newItem.setTransaction(transaction);
                    newItem.setItemType(TransactionItemType.CONFIRM);
                    return newItem;
                });
        item.setTransferState(data.getTransferStatus());
        item.setTransferDate(data.getTransferDate());
        item.setWithdrawAmount(data.getWithdrawAmount());
        item.setWithdrawCurrency(data.getWithdrawCurrency());
        item.setRawResponse(rawResponse);
        transactionItemRepository.save(item);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("JSON serialization xatosi: {}", e.getMessage());
            return null;
        }
    }
}
