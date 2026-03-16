package uz.agrobank.transactionclient.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import uz.agrobank.transactionclient.dto.ChequeStatusDto;

import java.util.Collections;
import java.util.List;

/**
 * Provides read access to cheque-status data stored in Oracle.
 *
 * <p>Key design decisions:
 * <ul>
 *   <li>Generic types are used throughout – raw {@code List} types have been replaced
 *       with {@code List<String>} / {@code List<ChequeStatusDto>} to give compile-time
 *       type safety and avoid unchecked-cast warnings.</li>
 *   <li>An early-return guard prevents an {@code ORA-00936} error that Oracle raises
 *       when an {@code IN} clause receives an empty collection.</li>
 *   <li>A {@code null} guard avoids a {@code NullPointerException} before we even
 *       reach the JDBC layer.</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class ChequeRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * Returns aggregated BM response counts for each cheque ID in the given list.
     *
     * @param chequeIds non-null list of cheque identifiers to look up
     * @return list of {@link ChequeStatusDto}; empty if {@code chequeIds} is empty
     * @throws IllegalArgumentException if {@code chequeIds} is {@code null}
     */
    public List<ChequeStatusDto> getChequeStatuses(List<String> chequeIds) {
        if (chequeIds == null) {
            throw new IllegalArgumentException("chequeIds must not be null");
        }
        // Oracle raises ORA-00936 for an empty IN () list, so short-circuit early.
        if (chequeIds.isEmpty()) {
            return Collections.emptyList();
        }

        String sql = """
                SELECT bmr.BM_REQUEST_ID,
                       bmr.CHEQUE_ID,
                       NVL(br.accepted, 0) AS accepted,
                       NVL(br.rejected, 0) AS rejected,
                       NVL(br.paid, 0)     AS paid
                FROM BM_CHEQUE_LIST bmr
                LEFT JOIN (
                        SELECT REQUEST_ID,
                               SUM(CASE WHEN MAIL_TYPE = '7050211' THEN 1 ELSE 0 END) AS accepted, -- accepted
                               SUM(CASE WHEN MAIL_TYPE = '7050213' THEN 1 ELSE 0 END) AS rejected, -- rejected
                               SUM(CASE WHEN MAIL_TYPE = '7050212' THEN 1 ELSE 0 END) AS paid      -- paid
                        FROM BM_RESPONSE
                        GROUP BY REQUEST_ID
                ) br ON br.REQUEST_ID = bmr.BM_REQUEST_ID
                WHERE bmr.CHEQUE_ID IN (:chequeIds)
                """;

        MapSqlParameterSource params = new MapSqlParameterSource();
        params.addValue("chequeIds", chequeIds);

        return jdbcTemplate.query(sql, params,
                (rs, rowNum) -> new ChequeStatusDto(
                        rs.getString("CHEQUE_ID"),
                        rs.getString("BM_REQUEST_ID"),
                        rs.getInt("accepted"),
                        rs.getInt("rejected"),
                        rs.getInt("paid")
                )
        );
    }
}
